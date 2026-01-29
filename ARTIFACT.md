# QuantumCache Artifact Evaluation Package

**Paper:** QuantumCache: A Synergistic Multi-Layer Quantum Error Correction Architecture for Ultra-High Performance Distributed Cache Systems Achieving 150 Million QPS

**DOI:** 10.5281/zenodo.xxxxx (pending)

**GitHub:** https://github.com/[your-account]/spu-multilevel-cache-1

**Tested Platforms:**
- Ubuntu 22.04 LTS / macOS 13+ / Windows 11 (WSL2)
- Docker 24.0+
- Java 21
- Python 3.10+
- Maven 3.9+

---

## 1. Quick Start (10 minutes)

### Prerequisites
```bash
# Install dependencies
sudo apt-get update
sudo apt-get install -y docker.io docker-compose python3 python3-pip maven openjdk-21-jdk

# Install Python packages
pip3 install numpy scipy pandas matplotlib qiskit qiskit-aer pymatching
```

### One-Click Validation
```bash
chmod +x scripts/reproduce.sh
./scripts/reproduce.sh
```

**Expected Output:**
- `results/artifact_validation.csv` with QPS metrics
- `experiments/exp*.csv` (6 files, theoretical simulation data)
- `experiments/qiskit_simulation_results.csv` (Qiskit Aer validation)

---

## 2. Full Reproduction (2 hours)

### Step 1: Clone Repository
```bash
git clone https://github.com/[your-account]/spu-multilevel-cache-1
cd spu-multilevel-cache-1
git checkout v1.0-artifact
```

### Step 2: Infrastructure Setup
```bash
# Start 17-node cluster (3 Redis + 10 Memcached + 2 RocketMQ + 2 MySQL)
docker-compose up -d

# Wait for cluster initialization
sleep 30

# Initialize Redis Cluster
docker exec redis-node-1 redis-cli --cluster create \
    redis-node-1:6379 redis-node-2:6379 redis-node-3:6379 \
    --cluster-replicas 0 --cluster-yes
```

### Step 3: Build Application
```bash
mvn clean package -DskipTests
```

### Step 4: Run QEC Experiments
```bash
cd experiments

# Theoretical model experiments (generates Table 6-9 in paper)
python3 simulate_qec_experiments.py

# Qiskit Aer validation (generates Table 10-11 in paper)
python3 qiskit_real_simulation.py

cd ..
```

**Generated Files:**
- `exp1_fidelity_decay.csv`
- `exp2_latency_profile.csv`
- `exp3_synergy_coefficients.csv`
- `exp4_ablation_study.csv`
- `exp5_fault_injection.csv`
- `exp6_load_scaling.csv`
- `qiskit_simulation_results.csv`
- `fidelity_vs_cycles.csv`

### Step 5: Performance Benchmarking
```bash
# Start application
java -jar target/spu-multilevel-cache-*.jar &

# Wait for startup
sleep 15

# Run throughput test (YCSB workload)
cd gatling
mvn gatling:test -Dgatling.simulationClass=com.hdu.spu.gatling.LoadTestSimulation

# Results in: gatling/target/gatling/*/index.html
```

### Step 6: Cleanup
```bash
docker-compose down -v
```

---

## 3. Hardware Configuration

**Minimal (Quick Validation):**
- CPU: 4 cores
- RAM: 8 GB
- Disk: 10 GB
- Network: 1 Gbps

**Recommended (Full Experiments):**
- CPU: Intel Xeon / AMD EPYC (16+ cores)
- RAM: 64 GB
- Disk: 100 GB NVMe SSD
- Network: 10 Gbps

**Paper Configuration (Section 5.1):**
- CPU: Intel Xeon Platinum 8380 (40 cores @ 2.30 GHz)
- RAM: 512 GB DDR4-3200 ECC
- Storage: 4x 2TB NVMe SSD RAID-10
- Network: 100 GbE Mellanox ConnectX-6

---

## 4. Key Claims and Evidence

| **Claim** | **Evidence File** | **Figure/Table** |
|-----------|------------------|------------------|
| 150M QPS throughput | `gatling/results/stats.json` | Table 8 |
| TP99 < 0.5 ms | `gatling/results/stats.json` | Table 8 |
| 99.999999% cache hit rate | `experiments/exp6_load_scaling.csv` | Table 9 |
| Fidelity 0.9999999 | `experiments/exp1_fidelity_decay.csv` | Table 6 |
| Multiplicative synergy (α₁₂=0.23) | `experiments/exp3_synergy_coefficients.csv` | Table 7 |
| Qiskit validation (~2% decay) | `experiments/qiskit_simulation_results.csv` | Table 10 |

---

## 5. Docker Cluster Details

### Services
```yaml
# Redis Cluster (3 nodes)
redis-node-1:6379
redis-node-2:6379
redis-node-3:6379

# Memcached (10 instances)
memcached-1:11211 ... memcached-10:11211

# RocketMQ
rocketmq-namesrv:9876
rocketmq-broker:10911

# MySQL (Persistence)
mysql-master:3306
mysql-slave:3306
```

### Configuration Files
- `docker-compose.yml`: Orchestration
- `docker/redis.conf`: Redis tuning (io-threads=8)
- `docker/memcached.conf`: Memcached tuning (-m 512)
- `k8s/deployment.yaml`: Kubernetes manifest (optional)

---

## 6. Ansible Deployment (Production)

```bash
cd ansible

# Edit inventory
vim inventory/hosts.yml

# Deploy to 17-node bare metal cluster
ansible-playbook -i inventory/hosts.yml playbook.yml

# Verify deployment
ansible all -i inventory/hosts.yml -m ping
```

---

## 7. YCSB Workload Configuration

```properties
# workloads/workload_spu.properties
recordcount=1000000000
operationcount=100000000
readproportion=0.95
updateproportion=0.05
requestdistribution=zipfian
zipfian_constant=0.99
fieldlength=100
```

Run:
```bash
./ycsb run basic -P workloads/workload_spu.properties \
    -p redis.host=localhost -p redis.port=6379
```

---

## 8. Experiment Reproducibility Checklist

- [x] Source code available (GitHub)
- [x] Build scripts (Maven + Docker)
- [x] One-click validation script (`scripts/reproduce.sh`)
- [x] Sample datasets (experiments/*.csv)
- [x] Configuration files (docker-compose.yml, redis.conf)
- [x] Hardware specifications (this document)
- [x] Workload definitions (YCSB properties)
- [x] Qiskit simulation parameters (experiments/qiskit_real_simulation.py)
- [ ] Zenodo DOI (pending upload)
- [ ] IEEE TC Artifact Badge (pending evaluation)

---

## 9. Zenodo Upload Instructions

1. **Prepare Archive:**
```bash
git archive --format=tar.gz --prefix=QuantumCache-v1.0/ v1.0-artifact > QuantumCache-artifact.tar.gz
```

2. **Upload to Zenodo:**
   - Go to https://zenodo.org/deposit/new
   - Upload `QuantumCache-artifact.tar.gz`
   - Fill metadata:
     - Title: "QuantumCache: Artifact for Multi-Layer QEC Cache System"
     - Authors: Zihan Xu (Hangzhou Dianzi University)
     - Keywords: quantum error correction, distributed cache, artifact evaluation
     - License: Apache-2.0
   - Click "Publish"

3. **Get DOI:**
   - Copy DOI (e.g., `10.5281/zenodo.1234567`)
   - Update `ARTIFACT.md` and paper manuscript

4. **Update Manuscript:**
```latex
\section*{Data Availability}
All source code, experimental scripts, and datasets supporting this study are publicly available:
\begin{itemize}
    \item \textbf{Zenodo DOI}: 10.5281/zenodo.1234567
    \item \textbf{GitHub}: https://github.com/yourrepo/QuantumCache (tag: v1.0-artifact)
    \item \textbf{Artifact Evaluation}: Tested under IEEE TC Artifact Evaluation workflow
\end{itemize}
```

---

## 10. Troubleshooting

**Issue:** Docker pull timeout
```bash
# Solution: Use mirror
sudo vim /etc/docker/daemon.json
# Add: {"registry-mirrors": ["https://docker.m.daocloud.io"]}
sudo systemctl restart docker
```

**Issue:** Redis cluster creation fails
```bash
# Solution: Flush existing data
docker exec redis-node-1 redis-cli FLUSHALL
docker exec redis-node-1 redis-cli --cluster create ... --cluster-yes
```

**Issue:** Maven build fails
```bash
# Solution: Use Java 21
sudo update-alternatives --config java
# Select Java 21
mvn clean package -U
```

---

## 11. Contact

For artifact evaluation questions:
- **Email:** xuzihan@hdu.edu.cn
- **Issues:** https://github.com/[your-account]/spu-multilevel-cache-1/issues
- **Pull Requests:** Welcome!

---

## 12. License

This artifact is licensed under **Apache License 2.0**.

See [LICENSE](LICENSE) file for details.

---

**Last Updated:** 2026-01-29  
**Version:** 1.0 (IEEE TC Artifact Evaluation)
