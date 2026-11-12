# Totem of Null — Session Handoff

**Date:** 2026-11-11
**Repo:** https://github.com/waqqyym1215/totemofnull (public, branch `master`)
**Local:** `C:\Users\miles\totemofnull-paper`
**Target:** Paper 26.2 (dev bundle `26.2.build.135-stable`), Java 25

---

## 1. What the plugin is supposed to do

A Paper plugin for **Minecraft 26.2**. The **Totem of Null** is a single, non-stackable totem that
summons **50 fake-player minions** to hunt any player who is **not** wearing full netherite armor
with the **Flow** trim. See `README.md` for the full, agreed design — it is the source of truth.

Key agreed rules (from the user, verbatim intent):
- Totem works **only via a keybind** (default `U`, rebindable in options). There is **only one** totem.
- If the totem "pops", **all players die** and the owner loses their perks.
- The owner gets their armor and tools automatically; their name shows as `________` in tab list and in-game.
- Bots: 50 at once, random alphanumeric names (e.g. `Vj2j34lkBJEKkls`), auto-die after **15 minutes**,
  do not respawn until the holder uses the keybind again.
- Bots drop everything **except** armor, sword, shield. Bots do not attack other mobs.
- Bots start with **64 golden apples**, eat one below **4 hearts**, drop them on death; other bots can pick them up.
- Bots carry an **Unbreaking III** shield in the offhand and raise it **1–3 seconds** when attacked or when a
  nearby player looks straight at them holding a weapon.
- Armor: netherite + **Flow** trim + **Protection IV** + **Mending** + **Unbreaking III** + **Curse of Vanishing**.
  Sword: **Unbreaking III** + **Curse of Vanishing**.
- Recipe: 1 Nether Star (center), 4 Netherite Blocks (edges), 4 Totems of Undying (corners).
- **Bots do NOT need to appear in the tab list.** (User clarified this; it removes ~1/3 of the fake-player work.)

---

## 2. Build status

| Component | State |
|---|---|
| Gradle + `paperweight-userdev 2.0.0-beta.24` + Paper dev bundle `26.2.build.135-stable` | ✅ Works, JDK 25 |
| `ArmorKit.java` | ✅ Written, compiles (verified against real API: `TrimPattern.FLOW`, `TrimMaterial.NETHERITE`) |
| `NameGenerator.java` | ✅ Written, compiles |
| `TotemOfNullPlugin.java`, `TotemListener.java`, `plugin.yml`, `config.yml` | ✅ Written, **NOT yet compiled** (they reference `BotManager`, which does not exist yet) |
| NMS fake-player layer | ⚠️ **In progress — this is the current frontier** |
| `harness/` module | ✅ Written, compiles, **runs on a real Paper 26.2 server** |

**The main plugin does not compile yet** because `TotemOfNullPlugin` references
`com.waqqyy.totemofnull.bot.BotManager`, which has not been written. That is expected and is the
next task.

---

## 3. The fake-player mechanism — PROVEN, with two known fixes

The harness (`harness/`) is a standalone plugin that proves the mechanism on a real server. It is
**not** part of the main plugin; it is verification scaffolding.

### What works

```
Connection(PacketFlow.SERVERBOUND)
  → EmbeddedChannel added to pipeline as "packet_handler"
  → connection.channel = channel          // makes Connection.isConnected() true
  → connection.setListenerForServerboundHandshake(new MemoryServerHandshakePacketListenerImpl(server, connection))
  → new ServerPlayer(server, level, GameProfile, ClientInformation.createDefault())
  → new ServerGamePacketListenerImpl(server, connection, player, CommonListenerCookie.createInitial(profile, false))
  → player.connection = listener
  → server.getPlayerList().placeNewPlayer(connection, player, cookie)
```

**Result: 50 bots spawn, are real entities, hold inventories, survive ticks, and cost no measurable
TPS (19.9–20.0 with 50 bots).**

### Two fixes that were required (and are NOT optional)

1. **`listener.handleAcceptPlayerLoad(null)`** after `placeNewPlayer`.
   Without it `ServerGamePacketListenerImpl.hasClientLoaded()` stays false, which makes
   `ServerPlayer.isInvulnerableTo()` return true for everything → the bot can never be damaged.

2. **Clear invulnerability flags.** Fake players are constructed with creative-mode abilities.
   `setGameMode()` does **not** clear them. You must do all of:
   ```java
   player.getAbilities().invulnerable = false;
   player.setInvulnerable(false);      // Entity.invulnerable — separate field
   player.onUpdateAbilities();
   player.setGameMode(GameType.SURVIVAL);
   ```
   `ServerPlayer.isInvulnerableTo()` → `Player` → `Avatar` → `LivingEntity` → `Entity.isInvulnerableToBase()`
   checks `Entity.invulnerable`, **and** `Player.isInvulnerableTo()` checks `Abilities.invulnerable`.
   Both must be false.

### Tab-list hiding — PROVEN

`CraftPlayer.unlistPlayer()` is Paper's supported API. `PlayerList.placeNewPlayer()` consults each
viewer's `CraftPlayer.isListed(bot)` before broadcasting the player-info packet.

**The unlisting must be done in BOTH directions, BEFORE `placeNewPlayer()`:**
```java
var craftPlayer = player.getBukkitEntity();
for (var online : server.getPlayerList().getPlayers()) {
    if (online != player) {
        online.getBukkitEntity().unlistPlayer(craftPlayer);  // hide bot from existing player
        craftPlayer.unlistPlayer(online.getBukkitEntity());  // hide existing player from bot
    }
}
server.getPlayerList().placeNewPlayer(connection, player, cookie);
```
Also handle `PlayerJoinEvent` so players joining later don't see the bots.

**Harness check for tab hiding passes both ways** (`Unlist mechanism works`, `Bots hidden from real players`).

### Open issue — damage check still FAILS

Latest harness run:
```
[PASS] Spawn 50 bots (spawned=50)
[PASS] All bots alive (alive=50)
[PASS] Bots hidden from real players (realViewers=0 listed=0)
[PASS] Unlist mechanism works (visibleToBots=0)
[PASS] Bots have inventories (checked=50)
[FAIL] Bot takes damage and dies (before=20.0 after=20.0 hurt=false invulnerable=false abilitiesInvuln=false invulnTo=true)
[PASS] Bots survive a tick (valid=50)
```

**Diagnosis so far:** `invulnerable=false` and `abilitiesInvuln=false`, yet `isInvulnerableTo(...) == true`.
The next diagnostic (already added to `HarnessPlugin.java`, **not yet run**) prints `isRemoved()` and
`getRemovalReason()`. Strong hypothesis: `Entity.isRemoved()` is true, because
`Entity.isInvulnerableToBase()` returns true when `isRemoved()` is true. Likely the bot is being
removed/ culled because it has no real client.

**Next step:** run the updated harness, read `removed=` / `reason=`, then address the removal cause.
If `isRemoved()` is the cause, investigate `Entity.setRemoved()`, `RemovalReason`, and whether an
unlisted player with an `EmbeddedChannel` is being culled by the player list or chunk tracking.

---

## 4. How to run the harness

A real Paper 26.2 server is required. A working headless setup exists at `C:\Users\miles\servertest`:

```bash
cd C:\Users\miles\servertest
java -Xms1G -Xmx2G -jar paper.jar nogui
```

- Paper jar: `paper-26.2-135.jar` (build 135, same as the plugin target)
- RCON enabled in `server.properties`: port `25575`, password `harness123`
- Harness jar copied to `plugins/totemofnull-harness-1.0.0.jar`
- **The server must be restarted** for a rebuilt harness jar to load (Paper does not hot-reload).

Drive it over RCON (a working Python snippet is in the session history; it authenticates then sends
a command and reads the response):
```
harness all      # full suite
harness spawn    # spawn one bot
harness status   # list bots + health + position
harness tps      # print TPS
harness clear    # remove all bots
```
Results are logged to `logs/latest.log` as `[PASS] ...` / `[FAIL] ...` lines prefixed `[TotemHarness]`.

Build the harness:
```bash
cd C:\Users\miles\totemofnull-paper\harness
../gradlew build -x test
# -> harness/build/libs/totemofnull-harness-1.0.0.jar
```

---

## 5. Key API facts (verified against the real 26.2 dev bundle)

| Fact | Detail |
|---|---|
| `GameProfile` | record: `id()`, `name()` — **not** `getName()` |
| `ClientInformation` | `createDefault()` |
| `CommonListenerCookie` | `createInitial(GameProfile, boolean)` |
| `ServerPlayer.connection` | public field, assign the listener directly |
| `handleAcceptPlayerLoad` | public; pass `null` (packet is only used for thread check) |
| `CraftPlayer.unlistPlayer` | public; operates on the **viewer's** own `unlistedEntities` set |
| `CraftPlayer.isListed` | public; use for checks |
| `Entity.invulnerable` | private; use `setInvulnerable(boolean)` |
| `Abilities.invulnerable` | **public field**, assign directly |
| `Player.onUpdateAbilities()` | public; pushes ability changes to the client |
| NMS `Inventory` | `getArmorContents()`, `getContents()`, `getSelectedItem()`, `setSelectedItem()` |
| NMS `Inventory` is **not** a Bukkit `PlayerInventory` | no `getHelmet()` / `getChestplate()` |

### Dead ends — do not retry these
- **Carpet Mod** — a Fabric client/server mod, not a Paper plugin. Cannot be used.
- **FakePlayer / FakePlayer-CE** — not published to Maven Central or JitPack, so it cannot be used as
  a library dependency. Would require forking their NMS code. Hard-depends on CommandAPI + PacketEvents.
- **FancyNpcs-based bots** — cosmetic packets only; cannot take damage, die, or fight.
- **`EmbeddedChannel` vs `LocalChannel`** — Paper's `Connection.isMemoryConnection()` checks for
  `LocalChannel`, but `EmbeddedChannel` was used in the harness and works (packets flow, `isConnected()` true).

---

## 6. Planned architecture (agreed with the user)

Isolate all NMS behind a small interface so a future swap (e.g. ProtocolLib) touches one file.

```
src/main/java/com/miles/totemofnull/
├── TotemOfNullPlugin.java   # ✅ exists — main class, config, recipe, commands
├── TotemListener.java       # ✅ exists — activation, anonymity, kit, loot filtering
├── ArmorKit.java            # ✅ exists — netherite + Flow + enchants
├── NameGenerator.java       # ✅ exists — random alphanumeric names
└── bot/
    ├── NmsBridge.java       # ⛔ was written then deleted — DO NOT write a 250-method interface
    ├── PaperNmsBridge.java  # TODO — implement the proven mechanism
    ├── NullBot.java         # TODO — wrapper: ServerPlayer + metadata
    └── BotManager.java      # TODO — spawn/tick/despawn, 15-min lifetime, loot
```

**Important lesson:** the previous session repeatedly generated a corrupted, bloated `NmsBridge`
interface (~250 methods, most unused, several with typos like `orgbukkit`). **Write the implementation
first, then extract only the methods actually called.** Keep the interface small.

**Do not hand-write `NmsBridge` from scratch.** Copy the working code from
`harness/src/main/java/com/miles/harness/FakePlayerFactory.java` and adapt it.

---

## 7. Immediate next steps

1. **Finish the damage check.** Run the updated harness, read `removed=` / `reason=`, fix the
   removal cause. Until bots can be damaged and killed, the plugin cannot work.
2. **Write `NullBot`** — a thin wrapper holding `ServerPlayer`, name, spawn time, golden-apple count,
   shield state.
3. **Write `PaperNmsBridge`** using the proven mechanism (copy from the harness).
4. **Write `BotManager`** — spawn 50 near the holder, tick them, enforce the 15-minute lifetime,
   handle removal. All game rules (targeting, apples, shield) go in plain Bukkit code here.
5. **Make the main plugin compile** (`./gradlew build` in the project root) — it currently fails
   only because `BotManager` is missing.
6. **Commit** — `src/` and `harness/` are currently **untracked**; nothing from this session is
   committed yet. `.gitignore` already excludes `.gradle/`, `build/`, `*.class`.

---

## 8. Other things to know

- **The user has a test server** and wants to install jars there. They chose "build the harness and
  give me the jar" over running it locally.
- **Anti-cheat risk:** invisible player entities are a classic cheat-client signature. If this goes on
  a public server, bots will likely be flagged. The user has not said whether it is public or private.
- **`gh` CLI** is installed at `C:\Users\miles\tools\gh\bin\gh.exe` (winget failed with a file lock).
  Git Credential Manager is the global `credential.helper`; logged in as `waqqyym1215`.
- **Rate limits:** this session hit repeated 429s from the model provider. Tool calls were
  interrupted; some work was lost and redone. Expect the same and keep commits small.
- **Do not run `git add` on `servertest/`** — it contains a 64 MB Paper jar and world data.
