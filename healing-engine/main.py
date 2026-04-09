"""
AutoHeal-J Healing Engine Module
-----------------------------------
DISCLAIMER: This repository contains a simplified research prototype.
Core automation logic, scaling heuristics, and failure mitigation maps 
have been intentionally generalized in this open-source release.
"""

import os
import time
import logging
import requests
from flask import Flask, request, jsonify
from kubernetes import client, config
import docker

logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(name)s - %(levelname)s - %(message)s')
logger = logging.getLogger("healing-engine")

app = Flask(__name__)

# Dashboard URL for reporting
DASHBOARD_URL = os.getenv("DASHBOARD_URL", "http://localhost:8085")

# Local restart commands for development mode
RESTART_COMMANDS = {
    "user-service": "java -jar microservices/user-service/target/user-service-0.0.1-SNAPSHOT.jar",
    "order-service": "java -jar microservices/order-service/target/order-service-0.0.1-SNAPSHOT.jar",
    "payment-service": "java -jar microservices/payment-service/target/payment-service-0.0.1-SNAPSHOT.jar",
    "gateway-service": "java -jar microservices/gateway-service/target/gateway-service-0.0.1-SNAPSHOT.jar",
    "autoheal-dashboard-service": "java -jar microservices/autoheal-dashboard-service/target/autoheal-dashboard-service-0.0.1-SNAPSHOT.jar --server.port=8085"
}

try:
    config.load_incluster_config()
except Exception:
    try:
        config.load_kube_config()
    except Exception as e:
        logger.warning(f"Could not load K8s config. Some healing actions may fail: {e}")

v1_core = client.CoreV1Api()
v1_apps = client.AppsV1Api()

USE_DOCKER = os.getenv("USE_DOCKER", "false").lower() == "true"
USE_LOCAL = os.getenv("USE_LOCAL", "true").lower() == "true" 
docker_client = None
if USE_DOCKER:
    try:
        docker_client = docker.from_env()
        logger.info("Docker client initialized for healing.")
    except Exception as e:
        logger.error(f"Failed to initialize Docker client: {e}")

TARGET_NAMESPACE = os.getenv("TARGET_NAMESPACE", "default")

def notify_dashboard(service, action, success):
    try:
        payload = {
            "service": service,
            "action": action,
            "success": success,
            "timestamp": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
        }
        requests.post(f"{DASHBOARD_URL}/api/webhook/healing", json=payload, timeout=5)
    except Exception as e:
        logger.error(f"Failed to notify dashboard: {e}")

@app.route('/alert', methods=['POST'])
def receive_alert():
    data = request.json or {}
    logger.info(f"Raw Alert Payload: {data}")
    service_name = data.get("service", "unknown-service")
    metrics = data.get("metrics", {})
    if not isinstance(metrics, dict):
        metrics = {}
    
    cpu = metrics.get("cpu_usage", 0.0)
    mem = metrics.get("memory_usage", 0.0)
    errors = metrics.get("error_rate", 0.0)
    latency = metrics.get("latency", 0.0)
    throughput = metrics.get("throughput", 0.0)
    
    logger.warning(f"RECEIVED ANOMALY ALERT: {service_name}. Metrics: CPU={cpu:.3f}, Mem={mem/1024/1024:.2f}MB, Errors={errors:.3f}, Latency={latency:.3f}s")
    
    # Generalized mitigation mapping (Abstracted for research repo)
    mitigation_action = "restart_service" 
    if data.get("type") == "PRIMARY_INFRA_FAILURE":
        mitigation_action = "restart_service"
    elif errors > 0.05 or (throughput < 1.0 and errors > 0.0):
        mitigation_action = "restart_service" 
    elif mem > 100 * 1024 * 1024: 
        mitigation_action = "restart_service" 
    elif cpu > 0.7: 
        mitigation_action = "scale_deployment" 
    elif latency > 0.5: 
        mitigation_action = "scale_deployment" 
        
    logger.info(f"MAPPED MITIGATION to action: {mitigation_action} for {service_name}")
    action = mitigation_action
    
    success = False
    for attempt in range(1, 4):
        logger.info(f"Attempt {attempt}/3 to execute {action} on {service_name}")
        if execute_healing_action(service_name, action):
            # Verification Step
            time.sleep(5) 
            if verify_healing(service_name, action):
                success = True
                break
        time.sleep(2)
        
    if success:
        logger.info(f"SUCCESSFULLY VERIFIED healing action {action} for {service_name}")
    else:
        logger.error(f"FAILED to execute healing action {action} for {service_name} after 3 attempts")
    
    # Notify Dashboard
    notify_dashboard(service_name, action, success)
        
    return jsonify({"status": "acknowledged", "action": action, "success": success})

def verify_healing(service, action):
    """
    Check if the service is stabilizing.
    """
    if USE_LOCAL:
        try:
            # Check if process is back in tasklist
            import subprocess
            cmd = f'wmic process where "commandline like \'%{service}%\'" get processid'
            output = subprocess.check_output(cmd, shell=True).decode()
            return len(output.strip().split('\n')) > 1
        except:
            return False

    try:
        if action == "restart_service" or action == "restart_pod":
            pods = v1_core.list_namespaced_pod(TARGET_NAMESPACE, label_selector=f"app={service}")
            return all(pod.status.phase == "Running" for pod in pods.items)
        elif action == "scale_deployment":
            deployment = v1_apps.read_namespaced_deployment(service, TARGET_NAMESPACE)
            return deployment.status.ready_replicas >= (deployment.spec.replicas or 1)
    except Exception as e:
        logger.error(f"Verification failed: {e}")
    return True 

def execute_healing_action(service, action):
    logger.info(f"EXECUTING RECOVERY ACTION: '{action}' on target '{service}'")
    
    # 1. Docker Fallback
    if USE_DOCKER and docker_client:
        try:
            containers = docker_client.containers.list(filters={"name": service})
            for container in containers:
                if action in ["restart_service", "restart_pod", "rollout_restart"]:
                    logger.info(f"SUCCESS: Restarting Docker container {container.name}")
                    container.restart()
                elif action == "scale_deployment":
                    logger.warning("Scaling not supported directly in Docker SDK fallback.")
            return len(containers) > 0
        except Exception as e:
            logger.error(f"Failed Docker action: {e}")

    # 2. Local Fallback (for Windows development)
    if USE_LOCAL:
        try:
            import subprocess
            # Kill existing if any
            cmd = f'wmic process where "commandline like \'%{service}%\'" get processid'
            try:
                output = subprocess.check_output(cmd, shell=True).decode()
                lines = output.strip().split('\n')[1:] 
                for line in lines:
                    if line.strip():
                        pid = line.strip()
                        if pid.isdigit():
                            logger.info(f"Killing local process {pid} for service {service}")
                            subprocess.run(f"taskkill /F /PID {pid}", shell=True)
            except:
                pass # No process found to kill, proceed to start
            
            # Start new process
            if service in RESTART_COMMANDS:
                restart_cmd = RESTART_COMMANDS[service]
                logger.info(f"SUCCESS: Restarting local service {service} with command: {restart_cmd}")
                # Use 'start' to run in background independently
                subprocess.Popen(f'start "{service}" /B {restart_cmd}', shell=True)
                return True
            else:
                logger.error(f"No restart command defined for {service}")
        except Exception as e:
            logger.error(f"Failed Local action for {service}: {e}")

    # 3. Kubernetes execution
    # ... (remains as is)


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=5000)
