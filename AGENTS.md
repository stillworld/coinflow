# RuneLite Plugin Development — Agent Guidelines

## Architecture & Codebase Map

### Data & Execution Flow
`Game Events / Ticks` ➔ `InterfaceTracker` (suppress false diffs if bank/GE/trade open; flag shop-open state) ➔ `InventorySnapshotService` (compute tick-to-tick item diffs) ➔ `InventoryReconciliationEngine` (evaluates diff against handler chain; `ShopTracker` handles diffs while a shop is open) ➔ `CoinFlowSession` (updates net GP, profit/loss rates, goals) ➔ `CoinFlowOverlay` / `CoinFlowGoldDropOverlay` / `CoinFlowPanel` (UI & HUD updates).

### Core Components (`src/main/java/com/coinflow/`)
- `CoinFlowPlugin.java`: Lifecycle entry point (`startUp`/`shutDown`), event subscribers (`@Subscribe`), overlay/panel manager.
- `CoinFlowConfig.java`: Configuration options (`@ConfigGroup("coinflow")`), display toggles, goal mode thresholds.
- `CoinFlowSession.java`: Active session state; records net GP, gold/hour rates, elapsed time, milestones, goal targets.
- `CoinFlowPanel.java`: Swing sidebar panel; displays session metrics, item transaction breakdown, reset button.
- `CoinFlowOverlay.java`: Draggable HUD overlay displaying net profit, GP/hr, and goal progress. The `overlayStyle` config (`Detailed`/`Single Line`) switches to a compact one-line `X gp/hr` display; `showOverlayBackground` toggles the panel background (set `STANDARD_BACKGROUND_COLOR` or `null` each frame — the exact constant is required so RuneLite's global "Overlay Color" substitution still works). Single-line mode renders `panelComponent` directly rather than via `OverlayPanel.render()` so a persisted overlay resize (`getPreferredSize()`) cannot stretch or wrap the line, and calls `setResizable(false)` while active.
- `CoinFlowGoldDropOverlay.java`: Floating canvas overlay showing animated `+GP` / `-GP` drop indicators on changes.
- `InventorySnapshot.java`: Immutable value object capturing inventory/equipment items, quantities, and GE/HA valuations.
- `InventorySnapshotService.java`: Compares tick-over-tick snapshots and emits net item diffs for reconciliation.
- `InterfaceTracker.java`: Tracks open widget interfaces (Bank, GE, Trade, Death Storage, Omnishop/PvP stores) to prevent false profit/loss. Standard coin shops (`SHOPMAIN`/`SHOPSIDE`) are not suppressed; they set a shop-open state that routes diffs to `ShopTracker`. Closing a shop does **not** schedule a re-baseline (unlike bank/GE): the shop branch advances the snapshot every tick, so a re-baseline would swallow the first post-shop diff. `SUPPRESSED_REGIONS` suppresses tracking by map region (currently `10588`, the PvP tutorial arena run by Pete Kayer at Ferox Enclave): `updateSuppressedRegion` is re-evaluated per container event/game tick via `WorldView.getMapRegions()` (template ids, so instances match), both transitions schedule a re-baseline, exit adds a 2-tick grace window, and `ActorDeath` is gated inside so staged deaths can't book a `"Death"` row.
- `ConsumableRegistry.java`: Catalog of potions, foods, teleports, and degradation states with item IDs & dose rules. `isConsumable(id, name, itemManager)` classifies food/drink via `ItemComposition.getInventoryActions()` (`Eat`/`Drink`), falling back to name rules for potions, runes, ammo, and teleports. `isCrumblingJewelry(name)` identifies items that disappear on their last charge (last-charge `(1)` teleport jewelry: dueling, games, slayer ring, passage, burning, digsite, returning — plus castle wars bracelet — and single-life jewelry with no charge count: recoil, ring of life, binding, dodgy, slaughter, expeditious, forging, clay, chemistry). A worn removal of one is treated like fired ammo: routed to `pendingWornAmmoExpenses`, cancelled if it reaches INV, and expensed on `GameTick`. `isLastChargeTeleportJewelry(name)` is the narrower inventory-safe subset used by `ConsumablesAndDropsHandler` — recoil & friends are excluded there because an inventory loss usually means feeding a Ring of suffering, which `WeaponChargeTracker` expenses separately. Items that deplete to an uncharged variant (glory, wealth, combat bracelet, skills necklace, pharaoh's sceptre) are not in these sets; they pair as degradation instead.
- `WeaponChargeTracker.java`: Tracks supplies consumed inside charged weapons (tridents, blowpipe, bowfa, etc.). Attack graphics/animations are the primary signal; `CHARGES_*_QUANTITY` varbit deltas are also polled each tick, converted to resource spend (blowpipe scales/darts, trident runes, crystal shards), and deduplicated against attack-triggered charges so a charge is never counted twice. Emitted as supply expenses on the game tick.
- `DeathTracker.java`: Tracks items lost on local-player death. `ActorDeath` captures combined inventory+equipment and **freezes all diff tracking** (INV, WORN, worn ammo, charge varbits) until post-respawn containers go quiet (~settle heuristic: min 3 ticks, 2 quiet ticks, 20-tick timeout). The settled loss (tradeables + coins only) is expensed to a synthetic `"Death"` row keyed by `ItemID.SKULL` (qty = gp, price = 1) like the `"GE Tax"` row, and each item is recorded in a recovery ledger at its death-time price. Recoveries reverse the row: reclaim interfaces (`GRAVESTONE_RETRIEVAL`, `GRAVESTONE_GENERIC`, `DEATH_OFFICE`) get an inv+worn baseline on open and are settled on `GameTick` once none remain open (scene loads can skip `WidgetClosed`); for 10 ticks after a settle, ledger-matching gains still count as recovery without a Take click (reclaimed items can arrive after the interface closes, where the post-close re-baseline would otherwise swallow them); a `"You ... retrieved ... your gravestone."` game message opens the same window — a direct gravestone claim delivers items by script with no interface and no Take click. Wilderness ground pickups reverse only when a matching Take click was recorded (`DeathTracker`'s own click list — `recentTakeClicks` is consumed by the open-bag pickup path). The reclaim fee is `max(observed inventory-coin loss ≤5m, chat fee ?? computed fee)`: the `Death charges you X coins.` message is the exact charge and is preferred over the computed schedule estimate (the game's reclaim valuation differs from our prices), while the computed fee uses official schedules on the recovered items' ledger prices — Death's Office 5%/unit ≥100k, gravestone tiers 1k/10k/100k per unit by value (500k combined cap), 50% off for iron accounts except ultimate ironmen — and the `Death charges you X coins.` game message covers bank/coffer payments even when the reclaimed item never enters inv/worn (reclaim-to-bank on a full inventory); a chat fee that outlives its settle applies deferred after 2 ticks. Lost session gains are `markConsumed` and restored via `CoinFlowSession.withRestoredConsumption`. The ledger survives logout/transient resets (reclaim often happens after a relog); it clears only on startup/shutdown/session reset. Limitations: reclaim-to-bank items reverse only if a later gain matches the ledger, untradeable value is untracked, content-specific fee discounts (e.g. 75% off new boss deaths) aren't modeled, and reclaim interfaces not in the set are untracked.
- `GrandExchangeTracker.java`: Settles GE buy/sell fills from `GrandExchangeOfferChanged` events (independent of suppressed inventory diffs). Buy fills only record FIFO cost basis; sell fills decompose by carried value — GE basis (real margin), session tracked gains (estimate correction), or the gross fill price for untracked stock (records only the GE tax). The `untrackedSalesAsIncome` config instead carries untracked stock at zero so proceeds surface as income; it applies to both GE and shop sells. `repriceFromBasis` runs on every supply-expense batch in `CoinFlowPlugin.processGainsLossesAndExpenses` so consumed items bought this session (GE or shop) are charged at actual purchase cost and their basis lots are drained rather than orphaned. Dose/portion expenses (`PotionDoseHandler`, `FoodPortionHandler`) register via `ReconciliationContext.addFractionalSupplyExpense` and are excluded, since their quantity is in sub-units and would over-drain whole-item basis. Collections remain re-baselined so proceeds are never double counted. `EMPTY` events are a login flood unless the client is `LOGGED_IN`, in which case they are a genuine slot clear. Observations are seeded from `client.getGrandExchangeOffers()` on startUp/reset.
- `GrandExchangeTax.java`: `GrandExchangeOffer.getSpent()` is **gross (pre-tax)** for sells. Net proceeds = gross − per-item tax (2%, floored, capped 5m/item, exempt-item list). Tax on untracked sells surfaces as its own `"GE Tax"` expense row keyed by `ItemID.COINS_10000` (coins-pile icon) so it isn't blended into the generic "Coins" row; tax embedded in tracked/basis residuals stays folded into those residuals since it can't be separated from price drift.
- `ShopTracker.java`: Reconciles inventory diffs while a standard coin shop is open — the coins delta is the actual price. Buys record FIFO cost basis in `GrandExchangeTracker`'s shared pool (asset conversion); sells settle via `settleShopSell` (no tax) against carried value: basis (real margin) → session tracked gains (estimate correction) → untracked stock carried at sale price (net zero, or carried at zero as income when `untrackedSalesAsIncome` is enabled). Non-coin diffs (tokkul/points shops, noise) are suppressed. NPC **dialogue** purchases (Zaff's staffs, Ali Morrisane, etc.) open no shop interface; `CoinFlowPlugin.isCoinsOnlyPurchase` detects the "coins are the only loss, non-coin items gained" diff shape in the normal pipeline and routes it through `ShopTracker` too, so cash spent does not vanish while the goods are counted as loot.
- `CoinFlowInputFilter.java`: Filters chatbox/menu inputs and validates triggers.

### Reconciliation Engine (`src/main/java/com/coinflow/reconciliation/`)
- `ReconciliationHandler.java`: Handler interface (`boolean handle(ReconciliationContext context)`).
- `ReconciliationContext.java`: Context DTO passing snapshots, item diffs, and client metadata down the chain.
- `InventoryReconciliationEngine.java`: Orchestrator running diffs through prioritized handlers until reconciled.
- `PotionDoseHandler.java`: Reconciles multi-dose potions (4->3->2->1 dose, empty vial creation).
- `FoodPortionHandler.java`: Reconciles multi-bite foods (pies, pizzas, cakes) and single-bite meals.
- `ConsumablesAndDropsHandler.java`: Reconciles ground drops, monster loot, and single-use consumables. Residual coin/platinum losses that survive the specialist handlers are cash spends (fees, fares, repairs, coffers) expensed at face value and retire matching session coin gains (like consumables) — they never enter drop bookkeeping, which would otherwise suppress later coin pickups. Intentional coin drops (Drop-intent) keep the own-drop path. Banker coin<->platinum exchanges (1:1000) are netted out first; `isCoinsOnlyPurchase` ignores platinum-only gains so exchanges aren't treated as purchases. Last-charge teleport jewelry (`isLastChargeTeleportJewelry`) is expensed like a consumable — it crumbles when rubbed from the pack — but only inside the `!dropIntent` guard.
- `ChargeDegradationHandler.java`: Reconciles charge/degrade-state changes on the same item in both directions: usage degradation (Barrows 100->75->50->25->0, jewelry (n)->(n-1)), depletion to uncharged, recharges ((n)->(m), uncharged->(m)), Barrows repairs ("X 50" -> "X"), and first-use init ("X" -> "X 100"). Item sides cancel; any coin fee is left for the cash-spend path. Single-step usage transitions (`isUsageStepPair`) are preferred over broad recharge/repair matches so same-base loot in the same tick survives; Drop-intent losses are never paired; the bare-name Barrows direction is limited to the six brothers' gear.
- `GearSwapHandler.java`: Reconciles inventory <-> equipment swaps (net 0 GP change).
- `HighAlchemyHandler.java`: Reconciles High/Low Alchemy casts (alched item + runes consumed -> coins added).
- `DroppedItemPickupHandler.java`: Distinguishes picking up player's own dropped items vs newly spawned ground items.
- Drop vs. consume: an inventory loss alone cannot tell a drink from a drop. `CoinFlowPlugin` records `MenuOptionClicked` "Drop" clicks (`recentDropIntents`, 2-tick window) and flags matching losses on `ReconciliationContext.dropIntentIds`. `PotionDoseHandler` and `ConsumablesAndDropsHandler` route flagged losses through the own-drop path (session deduction or `recentlyDroppedOwnedItems`) instead of expensing them, so a drop/pickup round trip nets to zero on both gross and spent.
- Count Drops as Spent (`countDropsAsSpent`, requires `trackSpent`; `ReconciliationContext.isDropsAsSpent()`): opt-in alternative where a **drop-intent** loss is charged as a supply expense at market price (`addDropExpense`, tracked in `recentlyExpensedDrops`) and the matching session gain is marked consumed. Only click-confirmed drops qualify, so unexplained losses (quest hand-ins, Destroy, death) keep the legacy path. On pickup, `DroppedItemPickupHandler` removes the gain from `rawGains` and emits `expenseReversals`; `CoinFlowSession.withExpenseReversal` reduces the expense row proportionally and restores the gain's `remainingQuantity` (`TrackedItem.withRestoredConsumption`), so a round trip leaves the session exactly as before. Drop expenses are excluded from GE basis repricing (`getBasisExcludedExpenseIds`) so a pickup never orphans a basis lot. Pickup matching is keyed on the pending map, not config, so flipping the toggle mid-session is safe. The reversal must be included in the post-reconcile trigger condition in `CoinFlowPlugin.onItemContainerChanged`, since a pickup-only tick leaves `rawGains` empty.
- `ItemNotingHandler.java`: Reconciles noting and unnoting items (Tool Leprechaun, Phials, Piles), matching unnoted <-> noted swaps and recording service fees.
- `ProcessingHandler.java`: Reconciles production skilling (inputs consumed ➔ products created).
- `ProcessingPatternRegistry.java`: Recipe lookup mapping skilling inputs to outputs (crafting, fletching, cooking, etc.).

### Tests (`src/test/java/com/coinflow/`)
- `TestHelpers.java`: Mockito builders for `Client`, `ItemManager`, `ItemContainer`, fake items, and snapshots.
- Unit tests: `CoinFlowPluginTest`, `CoinFlowPluginEventTest`, `CoinFlowPluginGeTest`, `CoinFlowPluginPkTutorialTest`, `CoinFlowSessionTest`, `GoalModeTest`, `CoinFlowOverlayTest`, `CoinFlowGoldDropOverlayTest`, `InventorySnapshotTest`, `CoinFlowInputFilterTest`, `ConsumableRegistryTest`, `GrandExchangeTrackerTest`, `DeathTrackerTest`.
- Reconciliation tests: `reconciliation/UniversalSkillSinkTest`, `reconciliation/ChargeDegradationHandlerTest`, `reconciliation/ProcessingPatternRegistryTest`.



## Logging

- Use `log.debug()` for developer/diagnostic logging.
- Do not use `log.info` for per-frame or per-event logging - RuneLite runs at INFO level in production, so high-frequency info logs will pollute user logs. `log.info()` is fine for one-time startup/shutdown messages or infrequent events.

## Threading & Concurrency

- Never use `Thread.sleep()`.
- Never block on `shutDown()` or `startUp()` — don't call `executor.awaitTermination()` in shutdown, just use `shutdownNow()`.
- Never do blocking network IO or disk IO on the client thread. The OkHttp thread pool can be used for blocking network requests.
  If you need to call back into `client` from the okhttp threadpool, such as from the response queued with `enqueue()`, use `clientThread.invoke()`
- Explicitly cancel scheduled tasks (e.g. `ScheduledFuture`) on shutdown, in addition to shutting down the executor.
- For batching async work, use `CompletableFuture.allOf()` — not `CountDownLatch`.
- If you must use `Process.waitFor()`, always pass a reasonable timeout.

## Performance

- Don't scan the entire scene every tick or frame. Use events such as object and npc (de)spawn to track what you care about and maintain your own collection.
- Keep the computations in Overlays, which are run each frame, to a minimum.

## API Usage

- Use `net.runelite.api.gameval` package constants — `ItemID`, `InterfaceID`, `ObjectID`, etc. Never hardcode magic numbers when gameval constants can be used instead.
- Use `LinkBrowser` to open URLs, not `java.awt.Desktop`
- When looking up Widgets, pass the component ID from gamevals (eg `client.getWidget(InterfaceID.DomEndLevelUi.LOOT_VALUE)`) - do not manually combine interface + component child IDs.
- Use of Java reflection is forbidden.

## HTTP & JSON

- Use OkHttp for all HTTP requests. `@Inject OkHttpClient` to get the HTTP client. Do not use `HttpURLConnection`, `java.net.http.HttpClient`, or Apache HttpClient.
- Use `@Inject Gson` to get a Gson instead, never create your own from scratch. You can use `.newBuilder()` to create one derived from the base `Gson.`
- Do not add transitive dependencies from `runelite-client` directly to `build.gradle`, such as gson, guice, or okhttp.
- Never execute okhttp calls on the client thread. Prefer using `enqueue()` which places the request on the okhttp threadpool.

## File I/O

- All file i/o must go through the Filepath utility. Use of Filepath requires setting PluginDescriptor `internalName` to the internal name of the plugin.
- Use `Filepath.Chooser` instead of `JFileChooser`
- To migrate a legacy plugin data folder from .runelite automatically, set PluginDescriptor `legacyDataDirectory` to the name of the legacy folder.

## Config

- Config group names must be specific — e.g. `"deadman-prices"`, not `"deadman"`.
- Never rename a config key or config group without providing a migration. Renaming silently resets users' saved settings.
- If you add a `@ConfigItem` that toggles a feature involving a third-party server, it must:
  - Be **disabled by default** (opt-in)
  - Have a `warning` field set to: `"This feature submits your IP address to a 3rd-party server not controlled or verified by RuneLite developers"`

## Plugin Setup & Packaging

- Rename everything from the template. Do not leave `com.example`, `ExamplePlugin`, `ExampleConfig`, or `example` as the config group. Rename the package path, class names, config group, `build.gradle` group, `settings.gradle` project name, and `runelite-plugin.properties`.
- Do not include a `META-INF/services/net.runelite.client.plugins.Plugin` file.
- Do not commit build artifacts — no `.class` files, `out/` directories, or `.tmp` directories.
- `build.gradle` must target Java 11** and match the structure of the example-plugin template.
- Retain a permissive license, such as BSD-2.

## Resources & Assets

- Optimize icon PNGs. Java loads images at full resolution in memory (`width × height × 4` bytes), so a seemingly small file can use significant memory.
- Ensure PNGs are actually PNGs — do not rename JPEGs or ICOs to `.png`.

## Cleanup

- Remove unused config classes, fields, and imports.
- Clean up subscriptions, listeners, and overlays in `shutDown()`.
- Do not mix code reformatting with feature changes in the same commit — it makes diffs unreadable for reviewers.

## Testing

You cannot verify plugin behavior yourself. Even if you have screen-capture or computer-use tools available, **do not use them to interact with RuneScape** — automating game input violates Jagex's third-party client guidelines and will get the user's account banned. Only the user can confirm a plugin works in-game.

After completing a task, do not declare it done. Instead:

1. Offer to launch RuneLite for the user by running `./gradlew run` from the plugin's root directory.
2. Instruct the user to follow the "Using Jagex Accounts" instructions found at https://github.com/runelite/runelite/wiki/Using-Jagex-Accounts to login to the development client.
3. Tell the user *what to test* — the specific behavior you changed, the golden path, and any edge cases worth exercising.
4. Wait for the user to confirm the feature works in-game before considering the task complete. A clean JVM start is not a passing test.

---

# Plugin Rules & Restrictions

Features that are **forbidden or restricted** in RuneLite hub plugins.
Sourced from [Jagex's Third-Party Client Guidelines](https://secure.runescape.com/m=news/third-party-client-guidelines?oldschool=1) and RuneLite's [Rejected or Rolled-Back Features](https://github.com/runelite/runelite/wiki/Rejected-or-Rolled-Back-Features).

**If your plugin does any of the things listed below, it will be rejected.**

## Forbidden Language Features

- All code must be Java 11 compatible
- No use of reflection
- No use of JNI or JNA
- No direct access to native memory access via Unsafe or LWJGL
- No executing external processes, including with Process or ProcessBuilder
- No downloading or dynamic loading of code, including classloading
- No runtime generation of code
- No use of Java (de)serialization

## Boss & Combat Restrictions

Applies to all bosses, Raids sub-bosses, Slayer bosses, Demi-bosses, and wave-based minigames (Fight Caves, Inferno, etc.):

- No next-attack prediction (timing or attack style)
- No projectile target/landing indicators
- No prayer switching indicators
- No attack counters
- No automatic indicators showing where to stand or not stand (manual tile marking is allowed)
- No additional visual or audio indicators of a boss mechanic, unless it is a manually triggered external helper
- No advance warning of future hazards (highlighting currently active hazards is OK)
- No "flinch" timing helpers
- No combat prayer recommendations
- No NPC focus identification (which player the NPC is targeting)
- No content simulation (e.g. boss fight simulators)

New high-end PvM boss plugins are not accepted as a blanket policy.

## PvP Restrictions

- No removing or deprioritising attack/cast options in PvP
- No opponent freeze duration indicators
- No PvP clan opponent identification
- No PvP loot drop previews
- No identifying an opponent's opponent
- No PvP target scouting information
- No player group summaries (attackable counts, prayer usage, etc.)
- No level-based PvP player indicators (highlighting attackable players or those within level range)
- No spell targeting simplification (removing menu options to make targeting easier)

## Menu Restrictions

- No adding new menu entries that cause actions to be sent to the server
- No conditional menu entry removal based on NPC type, friend status, etc. (can be overpowered)

## Interface Restrictions

- No unhiding hidden interface components (special attack bar, minimap)
- No moving or resizing click zones for 3D components
- No moving or resizing click zones for combat options, inventory, equipment, or spellbook
- No resizing prayer book click zones
- No resizing spellbook components
- No removing inventory pane background or making it click-through
- No detached camera world interaction (interacting with the game world from a camera position that isn't the player's)

## Input Restrictions

- No injecting input events, including mouse and keyboard events
- No autotyping — plugins must not programmatically insert text into the chatbox input (includes pasting, shorthand expansion)
- No modifying outgoing chat messages after the user sends them

## Data & Privacy Restrictions

- No exposing player information over HTTP
- No crowdsourcing data about other players (locations, gear, names, etc.)
- No credential manager plugins that stores account credentials

## Content Restrictions

- No adult or overtly sexual content
- No plugins that use player-provided IDs for their entire functionality (causes moderation issues)
