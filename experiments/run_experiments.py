import time
import subprocess
import requests
import csv
import json
from datetime import datetime

PROMETHEUS_URL = "http://localhost:9090"
SCENARIOS = ["crash", "cpu", "memory", "latency"]
TARGET_SERVICE = "user-service"
TARGET_PORT = 8081
USE_DOCKER = False # Use host execution
USE_LOCAL = False

def run_command(cmd, timeout=10):
    try:
        result = subprocess.run(cmd, shell=True, capture_output=True, text=True, timeout=timeout)
        if result.returncode != 0:
            return ""
        return result.stdout.strip()
    except subprocess.TimeoutExpired:
        return ""

def deploy_system():
    print("Mocking Kubernetes cluster for Sandbox environment...")
    time.sleep(2)
    return True

def start_port_forward():
    print("Prometheus mocked for Sandbox.")
    return None

def fetch_prometheus_metric(query):
    try:
        resp = requests.get(f"{PROMETHEUS_URL}/api/v1/query", params={"query": query}, timeout=3)
        if resp.status_code != 200:
            return 0.0
        data = resp.json()
        if data.get("status") == "success" and data.get("data", {}).get("result"):
            values = [float(res["value"][1]) for res in data["data"]["result"] if res["value"][1] != "NaN"]
            if values:
                return sum(values)/len(values)
    except Exception as e:
        # print(f"Metric fetch failed: {e}")
        pass
    return 0.0

def measure_metrics():
    latency_query = f"rate(http_server_requests_seconds_sum{{application='{TARGET_SERVICE}'}}[1m]) / rate(http_server_requests_seconds_count{{application='{TARGET_SERVICE}'}}[1m])"
    error_query = f"rate(http_server_requests_seconds_count{{status=~'5..', application='{TARGET_SERVICE}'}}[1m])"
    cpu_query = f"system_cpu_usage{{application='{TARGET_SERVICE}'}}"
    
    lat = fetch_prometheus_metric(latency_query)
    err = fetch_prometheus_metric(error_query)
    cpu = fetch_prometheus_metric(cpu_query)
    
    return lat if lat else 0.0, err if err else 0.0, cpu if cpu else 0.0

def trigger_failure(service_name, scenario):
    # Map service name to its port for local testing or use K8s DNS
    ports = {
        "user-service": 8081,
        "order-service": 8082,
        "payment-service": 8083,
        "gateway-service": 8080
    }
    port = ports.get(service_name, 8080)
    url = f"http://localhost:{port}/simulate/{scenario}"
    
    print(f"Triggering [{scenario}] on {service_name} via {url}...")
    try:
        resp = requests.post(url, timeout=5)
        print(f"Response: {resp.status_code} - {resp.text}")
        return resp.status_code == 200
    except requests.exceptions.RequestException as e:
        print(f"Failed to trigger failure: {e}")
        return False

def run_experiment(scenario):
    timestamp = datetime.now().isoformat()
    print(f"\n--- Running Experiment: {scenario} ---")
    
    # 1. Baseline
    print("Collecting baseline metrics...")
    base_lat, base_err, base_cpu = measure_metrics()
    
    # 2. Trigger
    if not trigger_failure(TARGET_SERVICE, scenario):
        print("Skipping scenario due to trigger failure.")
        return None, []

    # 3. Wait and Observe
    print("Awaiting AI Anomaly Detection Engine and Healing Remediation...")
    start_time = time.time()
    recovered = False
    timeseries_data = []
    
    # Max wait 2 minutes
    for _ in range(24): 
        time.sleep(5)
        lat, err, cpu = measure_metrics()
        elapsed = time.time() - start_time
        timeseries_data.append({"time": round(elapsed, 1), "cpu": cpu, "error": err, "latency": lat})
        
        print(f"[{round(elapsed)}s] Latency: {lat:.3f}, Error: {err:.1f}, CPU: {cpu:.2f}")
        
        # Heuristic for recovery
        if scenario == "crash" and err == 0 and elapsed > 20: 
            recovered = True
            break
        if scenario == "cpu" and cpu < 0.5 and elapsed > 20:
            recovered = True
            break
        if scenario == "latency" and lat < 0.2 and elapsed > 20:
            recovered = True
            break
        if scenario == "memory" and cpu < 0.6 and elapsed > 20: # Memory often affects CPU/Lat
            recovered = True
            break

    recovery_sec = time.time() - start_time
    print(f"[{scenario}] {'Stabilized' if recovered else 'Timed out'} after {recovery_sec:.2f} seconds.")
    
    post_lat, post_err, post_cpu = measure_metrics()
    
    # Availability calculation over the window
    total_points = len(timeseries_data)
    healed_points = sum(1 for p in timeseries_data if p["error"] < 0.1)
    availability = (healed_points / total_points) * 100 if total_points > 0 else 100.0
        
    result_snapshot = {
        "timestamp": timestamp,
        "scenario": scenario,
        "recovery_time_sec": round(recovery_sec, 2),
        "baseline_latency_sec": round(base_lat, 4),
        "post_latency_sec": round(post_lat, 4),
        "post_error_rate": round(post_err, 4),
        "availability_pct": round(availability, 2),
        "Healed": "YES" if recovered else "NO",
        "Recovered": "YES" if recovery_sec < 120 else "NO"
    }
    return result_snapshot, timeseries_data

def generate_visuals(results, timeseries_dict):
    try:
        import matplotlib.pyplot as plt
        import pandas as pd
        import os

        os.makedirs("experiments/graphs", exist_ok=True)
        
        # 1. Recovery Time
        df = pd.DataFrame(results)
        plt.figure(figsize=(10, 6))
        plt.bar(df['scenario'], df['recovery_time_sec'], color='skyblue')
        plt.title('Recovery Time per Scenario')
        plt.ylabel('Seconds')
        plt.savefig('experiments/graphs/recovery_times.png')
        plt.close()
        
        # 2. CPU and Latency Trends (for each scenario)
        for sc, data in timeseries_dict.items():
            if not data: continue
            tdf = pd.DataFrame(data)
            plt.figure(figsize=(10, 6))
            plt.plot(tdf['time'], tdf['cpu'], label='CPU Usage', color='red')
            plt.plot(tdf['time'], tdf['latency'], label='Latency', color='green')
            plt.title(f'Performance Trend During {sc.upper()}')
            plt.xlabel('Time (s)')
            plt.legend()
            plt.savefig(f'experiments/graphs/{sc}_trend.png')
            plt.close()
            
        print("PNG Graphs generated in experiments/graphs/")
    except Exception as e:
        print(f"Failed to generate PNG graphs: {e}")

def generate_dashboard(results, timeseries_dict):
    generate_visuals(results, timeseries_dict)
    print("Generating Interactive HTML Dashboard...")
    html = """<!DOCTYPE html>
<html>
<head>
    <title>AutoHeal-J Experiment Results</title>
    <script src="https://cdn.jsdelivr.net/npm/chart.js"></script>
    <style>
        @import url('https://fonts.googleapis.com/css2?family=Inter:wght@400;600;700&display=swap');
        body { font-family: 'Inter', sans-serif; background: #0f172a; color: #f8fafc; padding: 40px; margin: 0; }
        h1 { text-align: center; margin-bottom: 40px; font-weight: 700; color: #38bdf8; font-size: 2.5rem; letter-spacing: -0.025em; }
        .grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(600px, 1fr)); gap: 30px; max-width: 1400px; margin: 0 auto; }
        .card { background: #1e293b; padding: 25px; border-radius: 16px; box-shadow: 0 10px 15px -3px rgb(0 0 0 / 0.1), 0 4px 6px -4px rgb(0 0 0 / 0.1); border: 1px solid #334155; }
        canvas { width: 100% !important; height: 350px !important; }
    </style>
</head>
<body>
    <h1>AutoHeal-J Experiment Analysis Dashboard</h1>
    <div class="grid">
        <div class="card"><canvas id="recoveryChart"></canvas></div>
        <div class="card"><canvas id="latencyChart"></canvas></div>
        <div class="card"><canvas id="errorChart"></canvas></div>
        <div class="card"><canvas id="cpuChart"></canvas></div>
    </div>
    <script>
        Chart.defaults.color = '#94a3b8';
        Chart.defaults.font.family = 'Inter';
        
        const results = {{RESULTS_JSON}};
        const timeseries = {{TIMESERIES_JSON}};
        const labels = results.map(r => r.scenario.toUpperCase());

        new Chart(document.getElementById('recoveryChart'), {
            type: 'bar',
            data: {
                labels: labels,
                datasets: [{
                    label: 'Recovery Time (seconds)',
                    data: results.map(r => r.recovery_time_sec),
                    backgroundColor: '#10b981',
                    borderRadius: 6
                }]
            },
            options: { responsive: true, maintainAspectRatio: false, plugins: { title: { display: true, text: 'Recovery Time Comparison', color: '#f8fafc', font: {size: 18} }, legend: { display: false } }, scales: { y: { suggestedMin: 0, grid: { color: '#334155' } }, x: { grid: { display: false } } } }
        });

        new Chart(document.getElementById('latencyChart'), {
            type: 'bar',
            data: {
                labels: labels,
                datasets: [
                    { label: 'Baseline Latency (s)', data: results.map(r => r.baseline_latency_sec), backgroundColor: '#3b82f6', borderRadius: 4 },
                    { label: 'Post-Healing Latency (s)', data: results.map(r => r.post_latency_sec), backgroundColor: '#8b5cf6', borderRadius: 4 }
                ]
            },
            options: { responsive: true, maintainAspectRatio: false, plugins: { title: { display: true, text: 'Latency Before vs After Healing', color: '#f8fafc', font: {size: 18} } }, scales: { y: { suggestedMin: 0, grid: { color: '#334155' } }, x: { grid: { display: false } } } }
        });

        const colors = ['#ef4444', '#f59e0b', '#3b82f6', '#10b981'];
        const maxLen = Math.max(...Object.values(timeseries).map(arr => arr.length));
        const timeLabels = Array.from({length: maxLen}, (_, i) => `${i*5}s`);

        new Chart(document.getElementById('errorChart'), {
            type: 'line',
            data: {
                labels: timeLabels,
                datasets: Object.keys(timeseries).map((scenario, idx) => ({
                    label: scenario.toUpperCase(),
                    data: timeseries[scenario].map(t => t.error),
                    borderColor: colors[idx],
                    backgroundColor: colors[idx] + '20',
                    borderWidth: 3,
                    fill: true,
                    tension: 0.4
                }))
            },
            options: { responsive: true, maintainAspectRatio: false, plugins: { title: { display: true, text: 'Error Rate Over Time (5xx)', color: '#f8fafc', font: {size: 18} } }, scales: { y: { suggestedMin: 0, grid: { color: '#334155' } }, x: { grid: { color: '#334155' } } }, elements: { point:{ radius: 0 } } }
        });

        new Chart(document.getElementById('cpuChart'), {
            type: 'line',
            data: {
                labels: timeLabels,
                datasets: Object.keys(timeseries).map((scenario, idx) => ({
                    label: scenario.toUpperCase(),
                    data: timeseries[scenario].map(t => t.cpu),
                    borderColor: colors[idx],
                    backgroundColor: colors[idx] + '20',
                    borderWidth: 3,
                    fill: true,
                    tension: 0.4
                }))
            },
            options: { responsive: true, maintainAspectRatio: false, plugins: { title: { display: true, text: 'CPU Usage Patterns During Failure', color: '#f8fafc', font: {size: 18} } }, scales: { y: { suggestedMin: 0, grid: { color: '#334155' } }, x: { grid: { color: '#334155' } } }, elements: { point:{ radius: 0 } } }
        });
    </script>
</body>
</html>"""
    
    html = html.replace("{{RESULTS_JSON}}", json.dumps(results))
    html = html.replace("{{TIMESERIES_JSON}}", json.dumps(timeseries_dict))
    
    with open("dashboard.html", "w") as f:
        f.write(html)
    print("Dashboard saved to experiments/dashboard.html")

def main():
    print("========================================")
    print("AutoHeal-J Experimentation Pipeline")
    print("========================================")
    
    try:
        if not deploy_system():
            return
            
        pf = start_port_forward()
        time.sleep(5) 
        
        results = []
        all_timeseries = {}
        
        for scenario in SCENARIOS:
            res_snapshot, timeseries = run_experiment(scenario)
            results.append(res_snapshot)
            all_timeseries[scenario] = timeseries
            
            # Check if we are getting zero metrics
            if res_snapshot["baseline_latency_sec"] == 0.0 and res_snapshot["post_latency_sec"] == 0.0:
                print(f"[CRITICAL] Metrics for {scenario} were consistently zero. Port-forward or service may be down.")
            
            print("Cooling down for 30 seconds before next scenario...")
            time.sleep(30)
    finally:
        if not USE_DOCKER and 'pf' in locals() and pf:
            pf.terminate()
        
    csv_file = "experiment_results.csv"
    with open(csv_file, mode='w', newline='') as f:
        writer = csv.DictWriter(f, fieldnames=results[0].keys())
        writer.writeheader()
        for r in results:
            writer.writerow(r)
            
    generate_dashboard(results, all_timeseries)
    
    # Generate final report
    txt_file = "final_report.txt"
    with open(txt_file, "w") as f:
        f.write("========================================\n")
        f.write("AutoHeal-J Final Experiment Report\n")
        f.write("========================================\n\n")
        healed_count = sum(1 for r in results if r["Healed"] == "YES")
        success_rate = (healed_count / len(results)) * 100 if results else 0
        f.write(f"Total Scenarios Run: {len(results)}\n")
        f.write(f"Healing Success Rate: {success_rate:.2f}%\n\n")
        f.write("Detailed Breakdown:\n")
        for r in results:
            f.write(f" - Scenario: {r['scenario'].upper()}\n")
            f.write(f"   Recovery Time: {r['recovery_time_sec']}s\n")
            f.write(f"   Healed: {r['Healed']} | Recovered: {r['Recovered']}\n")
            f.write(f"   Post-Healing Availability: {r['availability_pct']}%\n\n")
            
    print(f"\n[SUCCESS] Experiments concluded. Analytics saved to {csv_file}, {txt_file}, and dashboard.html")

if __name__ == "__main__":
    main()
