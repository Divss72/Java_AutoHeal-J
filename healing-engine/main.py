"""
AutoHeal-J Healing Engine Module
-----------------------------------
Autonomous mitigation engine that listens for anomaly alerts and
orchestrates self-healing policies (rolling restarts, scaling, etc.).
"""

import os
import time
import datetime
import logging
import requests
from flask import Flask, request, jsonify
from kubernetes import client, config

logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(name)s - %(levelname)s - %(message)s')
logger = logging.getLogger("healing-engine")

app = Flask(__name__)

# Dashboard URL for reporting
DASHBOARD_URL = os.getenv("DASHBOARD_URL", "http://localhost:8085")

# Allowed services whitelist (Security Hardening: Prevents Command Injection / Arbitrary Targeting)
ALLOWED_SERVICES = {"user-service", "order-service", "payment-service", "gateway-service", "autoheal-dashboard-service"}

# Local restart commands for development mode
RESTART_COMMANDS = {
    "user-service": "java -jar services/user-service/target/user-service-0.0.1-SNAPSHOT.jar",
    "order-service": "java -jar services/order-service/target/order-service-0.0.1-SNAPSHOT.jar",
    "payment-service": "java -jar services/payment-service/target/payment-service-0.0.1-SNAPSHOT.jar",
    "gateway-service": "java -jar services/gateway-service/target/gateway-service-0.0.1-SNAPSHOT.jar",
    "autoheal-dashboard-service": "java -jar dashboard-service/target/autoheal-dashboard-service-0.0.1-SNAPSHOT.jar --server.port=8085"
}

is_k8s = False
try:
    config.load_incluster_config()
    is_k8s = True
    logger.info("Loaded in-cluster Kubernetes configuration.")
except Exception:
    try:
        config.load_kube_config()
        is_k8s = True
        logger.info("Loaded local kubeconfig.")
    except Exception as e:
        logger.warning(f"Could not load K8s config. Falling back to local/docker: {e}")

v1_core = client.CoreV1Api() if is_k8s else None
v1_apps = client.AppsV1Api() if is_k8s else None

USE_DOCKER = os.getenv("USE_DOCKER", "false").lower() == "true"
USE_LOCAL = os.getenv("USE_LOCAL", "false" if is_k8s else "true").lower() == "true"
TARGET_NAMESPACE = os.getenv("TARGET_NAMESPACE", "default")

# Cooldown tracking to prevent thrashing/cascading restart loops
COOLDOWN_PERIOD_SECONDS = int(os.getenv("COOLDOWN_PERIOD_SECONDS", "60"))
last_healing_actions = {}  # service -> timestamp

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

@app.route('/health', methods=['GET'])
def health():
    return jsonify({"status": "UP", "engine": "healing-engine", "mode": "k8s" if is_k8s else "local"})

@app.route('/alert', methods=['POST'])
def receive_alert():
    data = request.json or {}
    logger.info(f"Raw Alert Payload: {data}")
    service_name = data.get("service", "unknown-service")

    # Security Validation: Check against whitelist
    if service_name not in ALLOWED_SERVICES:
        logger.warning(f"SECURITY ALERT: Rejected untrusted service target: '{service_name}'")
        return jsonify({"status": "rejected", "error": "Invalid service target"}), 400

    # Cooldown Check: Prevent rapid re-healing thrashing
    now = time.time()
    last_action_time = last_healing_actions.get(service_name, 0)
    time_since_last = now - last_action_time
    if time_since_last < COOLDOWN_PERIOD_SECONDS:
        remaining = int(COOLDOWN_PERIOD_SECONDS - time_since_last)
        logger.info(f"COOLDOWN ACTIVE for {service_name}: Skipping duplicate mitigation ({remaining}s remaining)")
        return jsonify({
            "status": "in_cooldown",
            "service": service_name,
            "remaining_seconds": remaining
        }), 200

    metrics = data.get("metrics", {})
    if not isinstance(metrics, dict):
        metrics = {}
    
    cpu = metrics.get("cpu_usage", 0.0)
    mem = metrics.get("memory_usage", 0.0)
    errors = metrics.get("error_rate", 0.0)
    latency = metrics.get("latency", 0.0)
    throughput = metrics.get("throughput", 0.0)
    
    logger.warning(f"RECEIVED ANOMALY ALERT: {service_name}. Metrics: CPU={cpu:.3f}, Mem={mem/1024/1024:.2f}MB, Errors={errors:.3f}, Latency={latency:.3f}s")
    
    # Mitigation policy mapping
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
    
    # Record cooldown timestamp before executing
    last_healing_actions[service_name] = now
    
    success = False
    if execute_healing_action(service_name, action):
        success = verify_healing(service_name, action)
        
    if success:
        logger.info(f"SUCCESSFULLY VERIFIED healing action {action} for {service_name}")
    else:
        logger.error(f"FAILED to verify healing action {action} for {service_name}")
    
    notify_dashboard(service_name, action, success)
    return jsonify({"status": "acknowledged", "action": action, "success": success})

def verify_healing(service, action):
    """
    Check if the service is stabilizing by watching active (non-terminating) pods.
    """
    if USE_LOCAL:
        try:
            import subprocess
            cmd = f'wmic process where "commandline like \'%{service}%\'" get processid'
            output = subprocess.check_output(cmd, shell=True).decode()
            return len(output.strip().split('\n')) > 1
        except Exception:
            return False

    if is_k8s and v1_core and v1_apps:
        try:
            # Poll for up to 30 seconds for the new pod to enter Running state
            for _ in range(6):
                time.sleep(5)
                if action in ["restart_service", "restart_pod", "rollout_restart"]:
                    pods = v1_core.list_namespaced_pod(TARGET_NAMESPACE, label_selector=f"app={service}")
                    # Filter out terminating pods
                    active_pods = [p for p in pods.items if p.metadata.deletion_timestamp is None]
                    if active_pods and any(p.status.phase == "Running" for p in active_pods):
                        return True
                elif action == "scale_deployment":
                    deployment = v1_apps.read_namespaced_deployment(service, TARGET_NAMESPACE)
                    if deployment.status.ready_replicas and deployment.status.ready_replicas >= (deployment.spec.replicas or 1):
                        return True
        except Exception as e:
            logger.error(f"Verification check failed: {e}")
            return False

    return True

def execute_healing_action(service, action):
    logger.info(f"EXECUTING RECOVERY ACTION: '{action}' on target '{service}'")
    
    # 1. Kubernetes execution (Preferred when cluster is detected)
    if is_k8s and v1_apps:
        try:
            if action in ["restart_service", "restart_pod", "rollout_restart"]:
                logger.info(f"SUCCESS: Triggering Kubernetes rollout restart for deployment {service}")
                patch = {
                    'spec': {
                        'template': {
                            'metadata': {
                                'annotations': {
                                    'kubectl.kubernetes.io/restartedAt': datetime.datetime.now(datetime.timezone.utc).isoformat()
                                }
                            }
                        }
                    }
                }
                v1_apps.patch_namespaced_deployment(name=service, namespace=TARGET_NAMESPACE, body=patch)
                return True
            elif action == "scale_deployment":
                logger.info(f"SUCCESS: Scaling Kubernetes deployment {service}")
                deployment = v1_apps.read_namespaced_deployment(name=service, namespace=TARGET_NAMESPACE)
                current_replicas = deployment.spec.replicas or 1
                patch = {'spec': {'replicas': current_replicas + 1}}
                v1_apps.patch_namespaced_deployment(name=service, namespace=TARGET_NAMESPACE, body=patch)
                return True
        except Exception as e:
            logger.error(f"Failed Kubernetes action for {service}: {e}")
            return False

    # 2. Local fallback
    if USE_LOCAL:
        try:
            import subprocess
            if service in RESTART_COMMANDS:
                restart_cmd = RESTART_COMMANDS[service]
                logger.info(f"SUCCESS: Restarting local service {service} with command: {restart_cmd}")
                subprocess.Popen(f'start "{service}" /B {restart_cmd}', shell=True)
                return True
        except Exception as e:
            logger.error(f"Failed Local action for {service}: {e}")

    return False

if __name__ == "__main__":
    app.run(host="0.0.0.0", port=5000)
