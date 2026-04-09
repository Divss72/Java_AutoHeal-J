import socket

targets = ['127.0.0.1', '192.168.1.10', '172.29.160.1', '172.18.224.1']
port = 9090

print(f"Scanning for port {port} across possible targets...")
for target in targets:
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.settimeout(1.0)
    result = s.connect_ex((target, port))
    if result == 0:
        print(f"[FOUND] {target}:{port} is OPEN")
    else:
        print(f"[CLOSED] {target}:{port} is CLOSED")
    s.close()

# Also scan for Gateway (8080)
port = 8080
print(f"\nScanning for port {port} across possible targets...")
for target in targets:
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.settimeout(1.0)
    result = s.connect_ex((target, port))
    if result == 0:
        print(f"[FOUND] {target}:{port} is OPEN")
    else:
        print(f"[CLOSED] {target}:{port} is CLOSED")
    s.close()
