import asyncio
import httpx
import random
import time
import sys

# Configuration
BASE_URL = "http://localhost:8080"
ENDPOINTS = [
    "/api/users",
    "/api/orders",
    "/api/payments"
]
TARGET_RPS = 100  # Total requests per second
DURATION = 300     # Duration in seconds (5 minutes)

async def send_request(client, endpoint):
    url = f"{BASE_URL}{endpoint}"
    try:
        start_time = time.perf_counter()
        response = await client.get(url, timeout=10.0)
        end_time = time.perf_counter()
        # print(f"Request to {endpoint} took {end_time - start_time:.4f}s - Status: {response.status_code}")
    except Exception as e:
        print(f"Request to {endpoint} failed: {e}")

async def load_generator():
    print(f"Starting load generation on {BASE_URL}...")
    print(f"Target RPS: {TARGET_RPS}, Duration: {DURATION}s")
    
    async with httpx.AsyncClient() as client:
        start_time = time.time()
        while time.time() - start_time < DURATION:
            # Calculate how many requests to send in this batch to maintain RPS
            batch_start = time.perf_counter()
            
            tasks = []
            for _ in range(TARGET_RPS):
                endpoint = random.choice(ENDPOINTS)
                tasks.append(send_request(client, endpoint))
            
            await asyncio.gather(*tasks)
            
            # Sleep if we finished the batch faster than 1 second
            batch_duration = time.perf_counter() - batch_start
            if batch_duration < 1.0:
                await asyncio.sleep(1.0 - batch_duration)
            else:
                print(f"Warning: RPS target not met. Batch took {batch_duration:.2f}s")

if __name__ == "__main__":
    try:
        asyncio.run(load_generator())
    except KeyboardInterrupt:
        print("\nLoad generation stopped by user.")
    except Exception as e:
        print(f"\nLoad generation error: {e}")
