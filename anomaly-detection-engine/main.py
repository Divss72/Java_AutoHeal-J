"""
AutoHeal-J Anomaly Detection Module
-----------------------------------
DISCLAIMER: This repository contains a simplified research prototype.
Core optimization logic, specific telemetry heuristics, and granular algorithmic
tuning have been intentionally abstracted or generalized in this public release.
"""

import os
import time
import requests
import numpy as np
import logging
from sklearn.ensemble import IsolationForest as AbstractDetector

logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(name)s - %(levelname)s - %(message)s')
logger = logging.getLogger("anomaly-detection-engine")

PROMETHEUS_URL = os.getenv("PROMETHEUS_URL", "http://localhost:9090")
HEALING_ENGINE_URL = os.getenv("HEALING_ENGINE_URL", "http://localhost:5000/alert")
DASHBOARD_URL = os.getenv("DASHBOARD_URL", "http://localhost:8085")
QUERY_ENDPOINT = f"{PROMETHEUS_URL}/api/v1/query"
POLL_INTERVAL = int(os.getenv("POLL_INTERVAL", "5"))

# Abstracted parameters
ALPHA = float(os.getenv("CONTAMINATION", "0.1"))

TRACKED_ENTITIES = ["user-service", "order-service", "payment-service", "gateway-service"]

DATA_VECTOR_TEMPLATES = {
    "v1": "up{{application='{service}'}}",
    "v2": "system_cpu_usage{{application='{service}'}}",
    "v3": "jvm_memory_used_bytes{{application='{service}'}}",
    "v4": "rate(http_server_requests_seconds_count{{application='{service}'}}[1m])",
    "v5": "rate(http_server_requests_seconds_count{{status=~'5..', application='{service}'}}[1m])",
    "v6": "rate(http_server_requests_seconds_sum{{application='{service}'}}[1m]) / rate(http_server_requests_seconds_count{{application='{service}'}}[1m])"
}

def retrieve_telemetry(query):
    try:
        response = requests.get(QUERY_ENDPOINT, params={'query': query}, timeout=5)
        response.raise_for_status()
        data = response.json()
        if data.get('status') == 'success' and data.get('data', {}).get('result'):
            values = []
            for result in data['data']['result']:
                val_str = result['value'][1]
                if val_str != 'NaN':
                    values.append(float(val_str))
            if values:
                return sum(values) / len(values)
    except Exception as e:
        pass
    return 0.0

def main():
    logger.info(f"Starting Abstracted Engine. Prometheus: {PROMETHEUS_URL}")
    
    # Core algorithm obfuscation mapping
    active_models = {svc: AbstractDetector(contamination=ALPHA, random_state=42) for svc in TRACKED_ENTITIES}
    state_histories = {svc: [] for svc in TRACKED_ENTITIES}
    
    while True:
        for entity in TRACKED_ENTITIES:
            v_vector = []
            v_dict = {}
            primary_pulse = True
            
            for key, q_tpl in DATA_VECTOR_TEMPLATES.items():
                computation = q_tpl.format(service=entity)
                val = retrieve_telemetry(computation)
                v_dict[key] = val
                
                # Validation mapping abstracted
                if key == "v1" and val == 0.0:
                    primary_pulse = False
                
                if key != "v1":
                    v_vector.append(val)
            
            if not primary_pulse:
                logger.warning(f"CRITICAL STATE EXCEPTION: {entity}. Initiating safety protocol...")
                payload = {"service": entity, "metrics": str(v_dict), "score": -1.0, "type": "PRIMARY_INFRA_FAILURE"}
                try:
                    requests.post(HEALING_ENGINE_URL, json=payload, timeout=5)
                    requests.post(f"{DASHBOARD_URL}/api/webhook/anomaly", json=payload, timeout=5)
                except Exception:
                    pass
                continue

            v_vector = [0.0 if np.isnan(v) or np.isinf(v) else v for v in v_vector]
            state_histories[entity].append(v_vector)
            
            # Generalized heuristics trigger
            if len(state_histories[entity]) > 15:
                X_matrix = np.array(state_histories[entity])
                try:
                    active_models[entity].fit(X_matrix)
                    current_state = X_matrix[-1].reshape(1, -1)
                    deviation_score = active_models[entity].decision_function(current_state)[0]
                    binary_pred = active_models[entity].predict(current_state)[0]
                    
                    if binary_pred == -1:
                        logger.warning(f"THRESHOLD EXCEEDED: Heuristics matched for {entity}. Deviation: {deviation_score:.3f}")
                        payload = {"service": entity, "metrics": str(v_dict), "score": float(deviation_score), "type": "HEURISTIC_ANOMALY"}
                        try:
                            requests.post(HEALING_ENGINE_URL, json=payload, timeout=5)
                            requests.post(f"{DASHBOARD_URL}/api/webhook/anomaly", json=payload, timeout=5)
                        except Exception:
                            pass
                except Exception:
                    pass
                
                if len(state_histories[entity]) > 500:
                    state_histories[entity].pop(0)
            else:
                logger.info(f"Computing baseline bounds for {entity}... ({len(state_histories[entity])}/15)")

        time.sleep(POLL_INTERVAL)

if __name__ == "__main__":
    main()
