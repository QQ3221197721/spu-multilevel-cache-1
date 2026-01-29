# Reproducibility Package

## 1. Source Code
- Repository: https://github.com/[your-account]/spu-multilevel-cache-1
- Version: v1.0.0 (commit: [SHA])
- License: Apache-2.0

## 2. Datasets
- Theoretical simulation data: `experiments/exp*.csv`
- Qiskit simulation data: `experiments/qiskit_simulation_results.csv`
- Data license: CC BY 4.0
- DOI: (pending Zenodo upload)

## 3. Hardware Configuration
**Classical Baseline:**
- CPU: Intel Xeon Platinum 8380 (40 cores @ 2.30GHz)
- Memory: 512GB DDR4-3200 ECC
- Storage: 4x 2TB NVMe SSD RAID-10
- Network: 100GbE Mellanox ConnectX-6

**Redis Cluster Configuration:**
```yaml
# redis.conf used in experiments
maxmemory-policy allkeys-lru
hash-max-ziplist-entries 512
io-threads 8
io-threads-do-reads yes
```

**Memcached Configuration:**
```bash
memcached -m 512 -c 1024 -t 4 -I 10m
```

## 4. Quantum Simulation Parameters
- Simulator: Qiskit Aer 0.13.3
- Backend: AerSimulator(method='statevector')
- Noise model: IBM Brisbane (127-qubit Eagle r3)
  - T₁: 300 μs (measured range: 250-350 μs)
  - T₂: 150 μs (measured range: 120-180 μs)
  - 1Q gate error: 0.1% (median across all qubits)
  - 2Q gate error: 1.0% (median CX gate fidelity)
  - Readout error: 2.0%

## 5. One-Click Reproduction

### Docker-based (Recommended)
```bash
git clone https://github.com/[account]/spu-multilevel-cache-1
cd spu-multilevel-cache-1
docker-compose up -d  # Start infrastructure
python experiments/simulate_qec_experiments.py  # Generate data
python experiments/qiskit_real_simulation.py    # Qiskit validation
```

### Manual Setup
```bash
# Install dependencies
pip install -r requirements.txt
mvn clean install

# Run experiments
cd experiments
python simulate_qec_experiments.py
python qiskit_real_simulation.py

# Results will be in experiments/*.csv
```

## 6. Performance Benchmarking
```bash
# Throughput test (requires running service)
cd gatling
mvn gatling:test -Dgatling.simulationClass=LoadTestSimulation

# Latency profiling
wrk -t12 -c400 -d30s http://localhost:8080/api/cache/get?key=test
```

## 7. Artifact Evaluation Checklist
- [x] Source code with build instructions
- [x] Experimental scripts
- [x] Sample datasets
- [x] Configuration files
- [ ] Dockerized environment
- [ ] CI/CD pipeline (.github/workflows)
- [ ] Performance reproducibility report

## 8. Data Availability Statement
All experimental data supporting this study are available at:
- Raw CSV: `experiments/` directory
- Processed results: Integrated in manuscript tables
- Zenodo DOI: (will be provided upon publication)

## 9. Contact
For questions regarding reproduction:
- Email: xuzihan@hdu.edu.cn
- Issues: https://github.com/[account]/spu-multilevel-cache-1/issues
