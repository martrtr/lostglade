# Badyaga / LostGlade real-world simulation harness

This harness is intentionally **not** a generated Minecraft mock.

It is designed around the real LostGlade checkout:

- `scan_world.py` reads actual Anvil/NBT data from `world/region` and `world/entities`.
- `route_benchmark.py` benchmarks routes against the actual saved block geometry.
- `mechanics_report.py` reads live `config/lg2.json` + `config/lg2-glitches.json`, and hashes the exact LG2 source files it is calibrated against.
- production login/auth are outside the harness.

The intended workflow is:

1. Fast offline scan/profiling against the real `world/` snapshot.
2. Reproduce stalls/deaths with deterministic trace data.
3. Fix shared Badyaga policy, not simulator-only behavior.
4. Validate only failing scenarios on a localhost clone of the real server with an offline simulation account.
5. Never connect `martrtr` during simulation.

The localhost integration layer is intentionally separate because Mineflayer movement physics and a server running faster than 20 TPS do not necessarily share the same wall-clock assumptions. Fast strategy fuzzing and real protocol/physics validation should therefore be separate layers.
