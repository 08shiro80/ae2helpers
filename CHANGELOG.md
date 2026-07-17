## 1.0.2
- Fix: crafting results no longer get stuck in the machine — the pending result was dropped as soon as the crafting service reported 0, which happened before the crafting CPU had registered the job (and again on world load), stranding the output
- Fix: import card now works on pattern provider blocks left in the default "push to all sides" mode (previously it only worked when a single push direction was set, e.g. the cable part form)
- Add: Advanced AE pattern provider support (regular + small, block + part)

## 1.0.1
- Allow card to be sneak-clicked into pattern providers
- Add compatibility with me soul card mod
- Add PT_BR localization (@PrincessStellar)
- Fix class loading issues on dedicated servers
