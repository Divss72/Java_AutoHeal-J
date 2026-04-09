@echo off
echo --- AutoHeal-J Local Build ^& Startup ---
echo Note: Using Java 21 and Python 3.
echo.

set JAVA_HOME=%~dp0tools\jdk-21\jdk-21.0.6+7
set MAVEN_HOME=%~dp0tools\apache-maven-3.9.6
set PATH=%JAVA_HOME%\bin;%MAVEN_HOME%\bin;%PATH%

echo [1/3] Verifying Builds...
REM Core services are built in the root pom
REM Dashboard is built separately

echo [2/3] Starting Services in Background...

echo Starting User Service (8081)...
start "User Service" /B java -jar microservices/user-service/target/user-service-0.0.1-SNAPSHOT.jar

echo Starting Order Service (8082)...
start "Order Service" /B java -jar microservices/order-service/target/order-service-0.0.1-SNAPSHOT.jar

echo Starting Payment Service (8083)...
start "Payment Service" /B java -jar microservices/payment-service/target/payment-service-0.0.1-SNAPSHOT.jar

echo Starting Gateway Service (8080)...
start "Gateway Service" /B java -jar microservices/gateway-service/target/gateway-service-0.0.1-SNAPSHOT.jar

echo Starting Dashboard Service (8085)...
set OPENAI_API_KEY=YOUR_OPENAI_API_KEY
set PROMETHEUS_URL=http://localhost:9090
start "AutoHeal Dashboard" /B java -jar microservices/autoheal-dashboard-service/target/autoheal-dashboard-service-0.0.1-SNAPSHOT.jar --server.port=8085

echo [3/3] Starting Anomaly Detection and Healing Engines...
set HEALING_ENGINE_URL=http://localhost:5000/alert
set DASHBOARD_URL=http://localhost:8085
start "Anomaly Engine" /B python anomaly-detection-engine/main.py

set USE_DOCKER=false
set USE_LOCAL=true
start "Healing Engine" /B python healing-engine/main.py

echo.
echo =======================================================
echo System is starting up...
echo Gateway: http://localhost:8080
echo Dashboard: http://localhost:8085
echo Prometheus (Expected): http://localhost:9090
echo =======================================================

