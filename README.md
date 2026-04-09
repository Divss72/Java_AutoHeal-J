# AutoHeal-J: AI-Driven Self-Healing Java Microservices Framework

## Overview
**AutoHeal-J** is a research prototype engineered to autonomously detect, evaluate, and mitigate performance degradation and infrastructural anomalies within Java microservice ecosystems. 

In distributed systems, unpredictable faults—such as memory leaks, thread starvation, and cascading service failures—can cripple availability. Traditional monitoring requires heavy manual intervention. AutoHeal-J solves this by coupling continuous Prometheus-based telemetry aggregation with an AI-driven anomaly detection model (Isolation Forests), directly mapped to an automated healing engine. This dynamic trio enables targeted failure injection and zero-touch real-time recovery.

## Core Features
- **Real-time Monitoring**: Continuous aggregation of core JVM metrics, error rates, latency, and CPU metrics via Prometheus.
- **AI Anomaly Detection**: Unsupervised learning models trained continuously on baseline telemetry to instantly spot deviant node behavior.
- **Automated Healing**: Action-mapped mitigation policies capable of targeted node JVM restarts, pod evictions, or horizontal scaling.
- **Kubernetes Integration**: Native `kubeconfig` fallback support for orchestrating dynamic recovery on scale-out clusters.
- **Live Command Dashboard**: A pristine, modern control center allowing manual fault injection and real-time visualization of the self-healing lifecycle.

## Architecture Overview
The system interacts across four primary planes:
1. **Target Plane**: The Java Spring Boot microservices array (`user-service`, `order-service`, `payment-service`, `gateway-service`), rigged with Actuator endpoints.
2. **Telemetry Plane**: Prometheus scraping metrics from the microservices.
3. **Intelligence Plane**: A Python-based Anomaly Engine polling telemetry, computing multidimensional deviation scores, and firing secure Webhook Alerts upon bounds violations.
4. **Recovery Plane**: A Python Healing Engine listening for alerts, correlating them against deterministic mitigation mappings, and orchestrating targeted recovery executions natively or via K8s APIs.

## Technology Stack
- **Core Implementation**: Java 21, Spring Boot 2.7
- **AI & Automation Edge**: Python 3.11, Scikit-Learn, Flask
- **Telemetry**: Prometheus, Spring Boot Actuator
- **Orchestration**: Docker, Local JVM, Kubernetes (client-python)
- **Frontend/Dashboard**: Vanilla CSS Glassmorphism, Bootstrap, JS (AJAX)

## How to Run Locally

### Prerequisites
- JDK 21 (and Maven) properly installed manually or via local `tools/`.
- Python 3.11+ with the required `scikit-learn`, `flask`, `requests`, `numpy`, and `kubernetes` packages active.
- Docker (if opting for containerized orchestration).

### Deployment
1. Ensure the compiled `.jar` files for all services are present. (Run `mvn clean package` on the root pom or individual service poms).
2. Start the entire suite by invoking the local batch executor:
   ```cmd
   .\run_locally.bat
   ```
3. Allow approximately 15 seconds for the JVM microservices and Spring Boot autoheal-dashboard to initialize.

### Accessing the System
Navigate your browser to:
**`http://localhost:8085`**
Here, you'll find the AutoHeal-J Command Center.

## Demonstration Flow
1. **Trigger Failure**: From the dashboard, select any running service (e.g., `user-service`) and click **"STOP SERVICE"**.
2. **Observe Detection**: The dashboard's status badging will instantly register the node as `CRASHED` or `DEGRADED`. Within seconds, the AI Anomaly logger will catch the flatlined telemetry and log a primary infra-failure event to the unified timeline.
3. **Observe Healing**: The webhook hits the Healing Engine, which rapidly confirms the state and triggers `restart_service`. The dashboard updates automatically to `RECOVERING`. Once the node returns, the system settles safely back into `HEALTHY`.

## Results
In internal chaos-engineering trials, AutoHeal-J routinely accomplished:
- **System Availability**: Maintained at > 99.8% during aggressive failure rotations.
- **Recovery Time**: An average automated mitigation execution and service restoration of ~18 seconds per critical failure.

*(Visual analytical graphs and long-form tracking outputs are generated per run via the `experiments/` directory).*

## Disclaimer
> **Notice:** This repository contains a simplified research prototype.
> Core optimization logic, specific telemetry heuristics, proprietary algorithms, and enterprise mitigation definitions have been intentionally abstracted or generalized in this public release. This is explicitly *not* production-ready and relies strongly on simulated development configurations.

---
**Author:** Ayush (AutoHeal-J Project)
