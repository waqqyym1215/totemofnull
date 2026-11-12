# Totem of Null

A Paper plugin for **Minecraft 26.2** that adds the **Totem of Null**, a single, non-stackable
totem that summons a swarm of **50 fake-player minions** to hunt down anyone who isn't wearing
**full netherite armor with the Flow trim**.

The owner (holder of the totem) is anonymous: their name is hidden in the tab list and in-game,
and they automatically receive their netherite armor and tools.

---

## Contents

- [Requirements](#requirements)
- [Design Decisions](#design-decisions)
- [Items](#items)
- [Behavior](#behavior)
- [Recipe](#recipe)
- [Configuration](#configuration)
- [Build](#build)
- [Installation](#installation)
- [Known Limitations](#known-limitations)

---

## Requirements

| | |
|---|---|
| **Server software** | Paper (or a Paper fork) |
| **Minecraft version** | 26.2 |
| **Java** | 25+ |
| **Plugin installed?** | **Required.** This is a plugin, not a client-side mod — nothing works unless the server has it installed. |

The plugin uses **NMS** (net.minecraft.server) to spawn fake players, so it is tied to the exact
Paper build it was compiled against. See [Known Limitations](#known-limitations).

---

## Design Decisions

These are the decisions that shaped the plugin. They exist so a future agent (or you, months from
now) understands *why* things are built this way instead of re-litigating them.

### 1. Paper plugin, not a Fabric mod

The server must control the totem, the fake players, and the targeting rules. A client-side mod
can't do any of that reliably, and a plugin is the only way to guarantee "doesn't work unless the
server has it installed."

### 2. Fake players via NMS, not a library

Paper 26.2 has **no built-in fake-player API** (`FakePlayer`/`NPC` classes do not exist in the
API jar). Options were:

- **NMS `ServerPlayer` with a null/offline connection** — full control, no extra dependency.
- **ProtocolLib / fake-player library** — version-tolerant, but an extra dependency and another
  thing to keep compatible.

We chose **NMS** for control and zero extra dependencies. The cost is version-coupling (see
[Known Limitations](#known-limitations)).

### 3. Targeting: "not full netherite + Flow trim"

A player is a valid target if they are **not** wearing a complete set of netherite armor where
**every piece** has the **Flow** trim. Read from the armor's trim data component.

- Partial netherite (e.g. missing boots) → valid target.
- Full netherite, no trim → valid target.
- Full netherite with Flow trim on all four pieces → **exempt**.
- Full netherite with Flow trim on some pieces but not all → valid target (the rule is all-or-nothing).

### 4. One totem, one keybind, one swarm

- The totem is a **single item** (non-stackable, `maxStackSize = 1`).
- It activates **only via a keybind**, not on death like a vanilla totem.
- The keybind **defaults to `U`** and is **rebindable** in the options menu.
- When activated, it spawns **all 50 bots at once**.
- The owner has a **cooldown** (the bots must all die before the holder can summon them again).
- If the totem "pops" (is used/consumed), **all players die** and the owner loses their perks.

### 5. Bots are disposable

- Bots **automatically die after 15 minutes** and do **not** respawn until the holder uses the
  keybind again.
- Bots **drop everything** except their armor, sword, and shield.
- Bots **do not attack other mobs** — they only target valid players.
- Bots **pick up** golden apples dropped by other bots.

### 6. Bot behavior details

- **Names:** random alphanumeric strings (mixed case + digits), e.g. `Vj2j34lkBJEKkls`.
  Unique — checked against the player list so they never collide with a real player.
- **Golden apples:** each bot starts with **64 golden apples** and eats one automatically when
  below **4 hearts**. They drop them on death; other bots can pick them up and use them.
- **Shield:** every bot carries a shield in its offhand (Unbreaking III). They raise it for
  **1–3 seconds** when someone is attacking them, or when a player nearby is looking straight
  at them while holding a weapon.
- **Armor/weapons:** full netherite with Flow trim, Protection IV, Mending, Unbreaking III,
  Curse of Vanishing. Sword likewise Unbreaking III + Curse of Vanishing.

### 7. Owner anonymity

The holder of the totem is shown as `________` in the **tab list** and **in-game**.

### 8. The kit

The owner automatically receives their armor and tools on activation (and on respawn if they
still hold the totem — see config).

---

## Items

| Item | Notes |
|---|---|
| **Totem of Null** | Single, non-stackable. Activates on keybind. |
| **Null armor set** | Netherite + Flow trim + Protection IV + Mending + Unbreaking III + Curse of Vanishing. |
| **Null sword** | Netherite + Unbreaking III + Curse of Vanishing. |
| **Null shield** | Unbreaking III. Carried in the offhand. |

---

## Behavior

1. Holder presses the keybind (default `U`) while holding the Totem of Null.
2. The owner receives their full kit (armor + tools) automatically.
3. 50 bots spawn near the holder and begin hunting valid targets (anyone not in full
   Flow-trimmed netherite).
4. Bots fight, eat golden apples when low, raise shields defensively, and drop loot on death.
5. After 15 minutes all bots die. They will not respawn until the holder uses the keybind again.
6. If the totem pops, all players die and the owner loses their perks.

---

## Recipe

A shaped recipe:

```
[T] [N] [T]
[N] [W] [N]
[T] [N] [T]
```

| Symbol | Ingredient |
|---|---|
| `T` | Totem of Undying (4, in the corners) |
| `N` | Netherite Block (4, top/bottom/left/right) |
| `W` | Nether Star (1, center) |

---

## Configuration

`config.yml` (values are defaults):

```yaml
# Keybind that activates the totem. See
# https://minecraft.wiki/w/Controls#Keybindings for valid key names.
keybind: U

# Number of bots spawned per activation.
bot-count: 50

# How long (in minutes) bots live before automatically dying.
bot-lifetime-minutes: 15

# Hearts below which a bot eats a golden apple.
bot-eat-threshold: 4.0

# Golden apples each bot starts with.
bot-golden-apples: 64

# Minimum/maximum seconds a bot keeps its shield raised.
shield-raise-min-seconds: 1
shield-raise-max-seconds: 3

# Bot name length (random alphanumeric).
bot-name-length: 14

# Give the owner their kit on activation.
give-kit-on-activate: true

# Give the owner their kit on respawn while still holding the totem.
give-kit-on-respawn: true
```

---

## Build

Requires JDK 25 and an internet connection (downloads the Paper dev bundle once).

```bash
./gradlew build
```

Output: `build/libs/totemofnull-paper-<version>.jar`

---

## Installation

1. Copy the built jar into your server's `plugins/` folder.
2. Restart the server.
3. Verify with `/plugins` — `TotemOfNull` should be listed.
4. Give yourself the totem with the custom command registered by the plugin (see the
   `plugin.yml` command list — intended to be `/ton`).

---

## Known Limitations

- **NMS version coupling.** The plugin is compiled against Paper `26.2.build.135-stable`
  mappings. Updating the server will break it until it is recompiled against the new mappings.
- **Fake players need NMS.** Paper has no built-in fake-player API in this version, so the plugin
  must reach into `net.minecraft.server`. This is the main reason for the version coupling above.
- **Bots are not real players.** They have no client connection; behavior is driven by the plugin
  simulating movement, targeting, and combat through NMS.
