# CoinFlow — In-Game Manual Verification Checklist

A running live list of manual verification scenarios to test in-game before hub submission. Each test is formatted on one line with its action and expected financial tracking result.

---

## 1. Prayer
- [ ] **Bones on Altar**: Offer bones (e.g. Dragon bones) at a POH gilded or Chaos altar → Bones deducted as supply expense; net profit decreases by bone value.
- [ ] **Manual Bone Burying**: Bury bones from inventory via left-click/menu → Bones deducted as supply expense; net profit decreases by bone value.
- [ ] **Ash Scattering**: Scatter ashes (e.g. Infernal ashes) from inventory → Ashes deducted as supply expense; net profit decreases by ash value.
- [ ] **Arceuus Reanimation**: Cast Reanimate spell on an ensouled head (e.g. Ensouled dragon head) → Head deducted as supply expense on cast; runes deducted as Tier C consumables.
- [ ] **Bones Dropped (Negative Test)**: Drop bones on the ground without burying or altar offering → Bones treated as dropped owned item; zero supply expense charged.

---

## 2. Firemaking
- [ ] **Tinderbox Ground Fire**: Use tinderbox on logs (e.g. Willow logs) to light a fire on the floor → Log deducted as supply expense; net profit decreases by log value.
- [ ] **Forestry Campfire**: Add logs to an existing Forester campfire → Log deducted as supply expense on consumption tick.
- [ ] **Bruma Torch**: Light logs using a Bruma torch instead of a standard tinderbox → Log deducted as supply expense.
- [ ] **Logs Dropped (Negative Test)**: Drop logs on the ground without lighting → Logs treated as dropped owned item; zero supply expense charged.

---

## 3. Construction
- [ ] **Plank Furniture**: Build standard furniture using planks and nails (e.g. Oak larder, Mahogany table) → Planks and nails deducted as supply expenses.
- [ ] **Luxury Materials**: Build furniture requiring luxury items (e.g. Marble block, Gold leaf, Magic stone, Limestone brick) → Luxury items deducted as supply expenses.
- [ ] **Textiles & Clockwork**: Build furniture requiring Bolt of cloth or Clockwork → Cloth/clockwork deducted as supply expense.
- [ ] **POH Garden Planting**: Plant a bagged plant or tree in a garden hotspot → Bagged plant deducted as supply expense; zero phantom profit.
- [ ] **Plank Sack (Negative Test)**: Fill or empty planks from a Plank sack in inventory → Zero supply expense recorded and zero profit gained.

---

## 4. Runecrafting
- [ ] **Elemental Altar**: Craft Pure/Rune essence at an elemental altar (e.g. Fire altar) → Essence deducted as supply expense; crafted runes credited to session profit.
- [ ] **Catalytic / High-Tier Altar**: Craft Pure/Daeyalt essence at Nature, Death, or Blood altar → Essence deducted as supply expense; high-tier runes credited to profit.
- [ ] **Combination Runes**: Craft Lava/Steam/Smoke runes with pure essence and talisman/elemental runes → Both essence and elemental runes deducted as expenses; combination runes credited to profit.
- [ ] **Binding Necklace Degradation**: Craft combination runes until a worn Binding necklace crumbles to dust → Binding necklace deducted as supply expense upon destruction.
- [ ] **Essence Pouches**: Empty an essence pouch at the altar → Gained essence in inventory does not trigger false profit; crafted runes properly reflect essence cost.

---

## 5. Herblore
- [ ] **Herb Cleaning**: Clean a grimy herb (e.g. Grimy ranarr weed → Ranarr weed) → Net profit increases by the value difference; Spent increases by the grimy herb cost.
- [ ] **Unfinished Potions**: Mix clean herb with a vial of water → Herb and vial of water deducted as expenses; unf potion credited to profit.
- [ ] **Potion Finishing**: Add secondary ingredient (e.g. Snape grass) to unf potion → Unf potion and secondary deducted as expenses; finished potion credited to profit.
- [ ] **Ingredient Crushing**: Crush secondary with pestle and mortar (e.g. Blue dragon scale, Bird nest, Lava scale) → Base item consumed; crushed dust/shard credited.
- [ ] **Herb Tar**: Mix clean herb with swamp tar (e.g. Guam leaf + 15 swamp tar → 15 Guam tar) → 1 herb and 15 swamp tar deducted as expenses; tar credited to profit.
- [ ] **Divine Potions**: Add 4 crystal dust to a 4-dose potion (e.g. Super combat potion(4)) → 1 base potion and 4 crystal dust deducted; Divine potion credited to profit.
- [ ] **Extended Antifires**: Add 4 lava scale shards to Antifire potion(4) → Antifire potion and shards deducted; Extended antifire credited to profit.

---

## 6. Cooking
- [ ] **Fish & Meat Cooking**: Cook raw fish or meat on a fire or range (e.g. Raw shark → Shark) → Raw fish deducted as expense; cooked fish credited to profit.
- [ ] **Burnt Food (Failure)**: Burn a fish or meat (e.g. Burnt shark) → Raw food deducted as expense; burnt junk suppressed from profit.
- [ ] **Wine Making**: Add grapes to a jug of water (grapes + jug of water → jug of wine) → Grapes and jug of water deducted as expenses; jug of wine credited to profit.
- [ ] **Bad Wine (Failure)**: Fermentation produces a jug of bad wine → Grapes and jug of water deducted as expenses; bad wine suppressed from profit.
- [ ] **Dough Mixing**: Mix pot of flour with jug/bucket of water → Pot of flour and water container deducted; dough credited to profit.
- [ ] **Bread Baking**: Bake bread dough on a range → Bread dough deducted as expense; bread credited to profit (or burnt bread suppressed).
- [ ] **Burnt Page Drop Immunity**: Receive a Burnt page drop (e.g. from Wintertodt crate) while carrying raw food → Burnt page is NOT suppressed as cooking junk; full value credited to profit.

---

## 7. Fletching
- [ ] **Log Cutting (Bows)**: Cut logs into unstrung bows (e.g. Yew logs → Yew longbow (u)) → 1 log deducted per unstrung bow gained; bow credited to profit.
- [ ] **Bow Stringing**: String unstrung bow with bow string → Unstrung bow and 1 bow string deducted; finished bow credited to profit.
- [ ] **Shield Carving**: Carve logs into unstrung shield (e.g. Yew logs → Yew shield (u)) → Exactly 2 logs deducted per shield gained; shield credited to profit.
- [ ] **Shield Stringing**: String unstrung shield with bow string → Unstrung shield and exactly 2 bow strings deducted; finished shield credited to profit.
- [ ] **Arrow Assembly**: Feather arrow shafts into headless arrows, then attach tips (e.g. Broad/Rune/Amethyst arrowtips) → Shafts, feathers, and tips deducted; arrows credited.
- [ ] **Dart Assembly**: Feather dart tips with feathers (e.g. Mithril/Rune/Amethyst dart tips) → Tips and feathers deducted 1:1; darts credited to profit.
- [ ] **Bolt Tipping**: Tip bolts with gem or amethyst bolt tips (e.g. Broad bolts + Amethyst bolt tips → Amethyst broad bolts) → Base bolts and tips deducted; tipped bolts credited.
- [ ] **Crossbow Assembly & Stringing**: Combine limbs with stock, then attach crossbow string → Limbs, stock, and crossbow string deducted; finished crossbow credited.
- [ ] **Javelin Assembly**: Combine javelin shafts with feathers, then attach javelin heads → Shafts, feathers, and heads deducted; finished javelins credited.

---

## 8. Crafting
- [ ] **Gem Cutting**: Cut uncut gem with a chisel (e.g. Uncut diamond → Diamond) → Uncut gem deducted; cut gem credited to profit (crushed gems suppressed).
- [ ] **Amethyst Chisel Cutting**: Chisel amethyst into dart tips, arrowtips, bolt tips, or javelin heads → Amethyst consumed at correct ratio (8/15/15/5); tips credited to profit.
- [ ] **Pottery Forming**: Form soft clay on pottery wheel into unfired pottery (e.g. Unfired bowl) → Soft clay deducted 1:1; unfired pottery credited to profit.
- [ ] **Molten Glass Making**: Smelt seaweed or giant seaweed with bucket of sand on furnace or via Superglass Make → Seaweed and sand deducted (1:1 or 1:6); molten glass credited.
- [ ] **Glassblowing**: Blow molten glass with glassblowing pipe into unpowered orbs or vials → Molten glass deducted; blown items credited without byproduct suppression.
- [ ] **Jewellery Smelting**: Smelt gold/silver bar with gem in furnace into rings/necklaces/amulets → Bar and gem deducted; jewellery credited to profit.
- [ ] **Amulet Stringing**: String unstrung amulet with ball of wool → Unstrung amulet and ball of wool deducted; finished amulet credited to profit.
- [ ] **Dragonhide Armor**: Craft d'hide body, chaps, or vambs with needle and thread → Correct quantity of dragon leather deducted (1/2/3); armor credited to profit.
- [ ] **Battlestaff Orbs**: Attach elemental orb to battlestaff (e.g. Air orb + Battlestaff → Air battlestaff) → Orb and battlestaff deducted; battlestaff credited to profit.

---

## 9. Smithing
- [ ] **Ore Smelting**: Smelt iron/steel/mithril/adamant/runite ore into bars at a furnace → Primary ore and coal (2/4/6/8) deducted; bars credited to profit.
- [ ] **Anvil Equipment Forging**: Hammer bars on an anvil into weapons or armor (1 to 5 bars) → Exact bar quantity deducted; forged equipment credited to profit.
- [ ] **Cannonball Casting**: Smelt steel bar with ammo mould into cannonballs → 1 steel bar deducted per 4 cannonballs gained; cannonballs credited to profit.

---

## 10. Farming
- [ ] **Patch Planting (Seeds)**: Rake and plant herb, allotment, or flower seeds → Seeds deducted as supply expense; net profit decreases by seed value.
- [ ] **Tree Planting (Saplings)**: Plant tree or fruit tree sapling in a cleared patch → Sapling deducted as supply expense; net profit decreases by sapling value.
- [ ] **Compost Application**: Treat patch with compost, supercompost, or ultracompost → Compost deducted as supply expense.
- [ ] **Plant Cure**: Apply plant cure to a diseased patch → Plant cure bottle deducted as supply expense.
- [ ] **Farmer Crop Protection**: Pay farmer to protect patch (e.g. baskets of apples, sweetcorn) → Produce deducted as Tier C supply expense.
- [ ] **Bottomless Compost Bucket (Negative Test)**: Use charges from a bottomless bucket → Zero item loss triggered; bucket remains intact with zero phantom expense.

---

## 11. Hunter
- [ ] **Deadfall Trap Construction**: Build a deadfall trap consuming a log (e.g. Willow logs) → Log deducted as supply expense upon trap capture.
- [ ] **Trap Baiting**: Place raw beef, raw chicken, or fishing bait into a trap → Bait deducted as supply expense upon trap setup.
- [ ] **Hunter Loot Drops (Negative Test)**: Drop kebbit claws, spikes, furs, or bones on the ground → Zero supply expense charged; discarded loot treated as uncollected drops.

---

## 12. Magic & High Alchemy
- [ ] **High Alchemy Profit Margin**: Cast High Alchemy on an item worth more than nature rune + item cost → True net margin (alchemy value minus item GE cost minus nature rune) credited to profit.
- [ ] **Gold Drop Overlay**: High alch an item exceeding the configured minimum gold drop threshold → Golden "+X gp" text floats above the player with the item sprite.
- [ ] **Plank Make Transmutation**: Cast Plank Make on mahogany/oak logs → Logs, runes, and coin cost deducted; planks credited to profit.
- [ ] **Combat / Teleport Spells**: Cast offensive spells or teleport spells → Elemental and catalyst runes deducted as Tier C consumables.

---

## 13. Combat, Consumables & Gear Swaps
- [ ] **Food & Potions**: Eat food (sharks, karambwans, anglerfish) or drink potions in combat → Food/potions deducted as supply expenses.
- [ ] **Multi-Dose Potions**: Drink a 4-dose potion down to 3, 2, 1 doses → 1 dose cost deducted as supply expense; remaining potion updated in snapshot.
- [ ] **Potion Combining & Decanting (Negative Test)**: Use a potion on another potion to decant/combine doses (e.g. two 3-dose potions into a 4-dose and a 2-dose, or two 2-dose potions into a 4-dose and empty vial) → Zero net doses consumed; 0 profit, 0 expenses, and 0 spent recorded.
- [ ] **Multi-Bite Food Portions (Pies & Cakes)**: Eat multi-bite foods (e.g. Summer pie → Half a summer pie; Cake / Chocolate cake → 2/3 cake → Slice) → Each bite deducted as 1 portion expense; remaining portion is not credited as false profit.
- [ ] **Multi-Portion Batch Consumption**: Consume multiple food portions across a single inventory update → Exact count of portions consumed deducted as expenses; remaining items preserved without dropping quantities.
- [ ] **Charged Jewelry & Teleport Devices (Negative Test)**: Rub charges on an Amulet of glory, Ring of dueling, Games necklace, Slayer ring, or Combat bracelet down to lower charges or uncharged (both from inventory and while equipped) → Zero expenses charged; degraded ring/jewelry left behind is not credited as false loot profit.
- [ ] **Dropping Charged Jewelry (Negative Test)**: Drop a 4-charge jewelry item (e.g. Ring of dueling(4)) on the ground → Treated as a dropped owned item; zero consumable supply expense charged.
- [ ] **Rune Pouch Consumption**: Cast spells consuming runes stored inside a carried Rune Pouch → Rune pouch varbits sync rune losses and deduct supply expenses.
- [ ] **Ammunition**: Fire arrows, bolts, darts, or blowpipe scales → Consumed ammo deducted as supply expenses.
- [ ] **Gear Swaps (Negative Test)**: Swap equipped weapons and armor (e.g. switch Whip to Blowpipe) → Zero gain or loss recorded in session profit.
- [ ] **Bank Deposit / Withdrawal (Negative Test)**: Deposit or withdraw inventory items at any bank or bank chest → Zero profit, loss, or expense recorded.
- [ ] **Grand Exchange Direct Collect (Negative Test)**: Right-click "Collect" on a Grand Exchange clerk, banker, or booth with pending sold items/coins → Collected coins and items are absorbed into baseline; zero session profit recorded.
- [ ] **Inventory Tab Return Grace Period**: Close a bank or GE side-interface by clicking directly back onto the standard Inventory tab → Re-baselines inventory across the 2-tick grace window with zero false profit recorded.
- [ ] **Multi-Packet In-Flight Withdrawals**: Withdraw multiple distinct item/coin stacks from the bank and close immediately → All withdrawal packets arriving within the grace period are absorbed with zero phantom profit.

---

## 14. Wilderness & Looting Bag
- [ ] **Checking Pre-Filled Bag After Login (Negative Test)**: Log in carrying a looting bag containing pre-existing items, and right-click "Check" immediately after login → Zero profit or expenses credited; total session profit remains 0 gp.
- [ ] **Ground Loot Direct Pickup into Open Bag**: Kill a monster in the Wilderness with an open looting bag (`Looting bag (open)`) and pick up a tradeable drop from the ground (e.g. Dragon bones, Rune scimitar) → Profit is immediately credited on pickup and gold drop overlay appears without needing to "Check" the bag.
- [ ] **Subsequent Looting Bag Check (Negative Test)**: Right-click the looting bag in Wilderness and click "Check" after items were picked up into it → Zero duplicate profit or expenses credited; total session profit remains unchanged.
- [ ] **Manual Inventory Store into Bag**: Use an item from inventory on the looting bag to deposit it → Zero profit gained and zero supply expense charged; transfer is financial neutral.
- [ ] **Bank Deposit Looting Bag**: Click "Deposit loot" or deposit bag contents at a bank chest → Zero expense or loss charged; deposited items do not affect session profit.
- [ ] **Toggling Bag Open/Closed (Negative Test)**: Click "Open" or "Close" on a looting bag in inventory → Zero profit or loss recorded.

---

## 15. Gem Bag & Gem Sack
- [ ] **Direct Ground Pickup into Open Gem Bag**: Carry an open gem bag (`Open gem bag` or `Open gem sack`) and pick up an uncut gem from the ground (e.g. Uncut ruby, Uncut diamond, Uncut dragonstone) → Gem enters the bag directly without occupying an inventory slot; profit is immediately credited to session profit and gold drop overlay triggers.
- [ ] **Filling Gem Bag from Inventory ("Fill" / "Use") (Negative Test)**: Have uncut gems in inventory and click "Fill" on the Gem bag/sack, or use an uncut gem on the bag → Gems are stored into the bag; zero supply expense or dropped item loss is deducted.
- [ ] **Emptying Gem Bag into Inventory ("Empty") (Negative Test)**: Right-click "Empty" on the Gem bag/sack to withdraw stored gems into inventory (or bank deposit) → Gems enter inventory; zero duplicate profit or loot is credited.
- [ ] **Toggling Open / Close State (Negative Test)**: Click "Open" or "Close" on a Gem bag, Gem sack, Gem pouch, Gem satchel, or Gem tote in inventory → Item switches state with zero profit, loss, or expense recorded.
- [ ] **Mining / Skilling Auto-Deposit into Open Bag**: Mine a gem rock, mine ore with a charged Amulet of glory, or thieve a gem stall while carrying an open gem bag → Game displays chat message confirming automatic storage into the bag; uncut gem is credited to session profit with gold drop overlay.
- [ ] **Semi-Precious Gems with Upgraded Gem Sack**: Carry an open Gem sack (or pouch/satchel/tote) and pick up or harvest uncut opal, jade, or red topaz → Semi-precious gem auto-collection is credited to session profit.

---

## 16. Equipped Ammo, Thrown Weapons, Dizana's Quiver & Master Scroll Book
- [ ] **Equipped Ammo Fired in Combat**: Equip arrows or bolts (e.g. Broad bolts, Diamond bolts (e), Amethyst arrows) in the equipment ammo slot and fire in combat → Each projectile consumed is deducted as a supply expense at active GE price; session profit decreases accordingly.
- [ ] **Equipped Ammo Swapping (Negative Test)**: Swap equipped ammo with different ammo from inventory (e.g. Ruby bolts (e) <-> Diamond bolts (e)) → Swapped items are recognized as gear swaps; zero supply expense or profit recorded.
- [ ] **Unequipping Ammo to Inventory (Negative Test)**: Unequip arrows or bolts directly into inventory → Items transfer to inventory with zero supply expense or profit recorded.
- [ ] **Thrown Weapons Fired in Combat**: Equip darts, knives, or chinchompas (e.g. Dragon darts, Black chinchompas) in the weapon slot and attack in combat → Each thrown weapon consumed is deducted as a supply expense at active GE price.
- [ ] **Thrown Weapon Swapping (Negative Test)**: Swap equipped thrown weapons with inventory weapons or unequip them → Zero supply expense or profit recorded.
- [ ] **Dizana's Quiver Ammo Consumption**: Carry or wear Dizana's Quiver loaded with arrows/bolts or Sunfire splinters and fire in combat → Projectiles and splinters consumed from the quiver ammo container are deducted as supply expenses.
- [ ] **Dizana's Quiver Loading & Unloading (Negative Test)**: Load arrows, bolts, or Sunfire splinters from inventory into Dizana's Quiver, or unload ammo from the quiver into inventory → Zero supply expense or profit recorded (transfers remain financially neutral).
- [ ] **Master Scroll Book Teleport Use**: Carry a Master Scroll Book loaded with teleport scrolls (e.g. Nardah, Zul-andra, Rev cave scrolls) and use a teleport charge → Decrement in the book's scroll varbit is deducted as a supply expense at the active GE price of the teleport scroll.
- [ ] **Master Scroll Book Loading & Unloading (Negative Test)**: Add teleport scrolls into the Master Scroll Book or remove scrolls into inventory → Total combined count of loose scrolls and stored scrolls remains unchanged; zero supply expense or profit recorded.
- [ ] **Empty-to-Charged Master Scroll Book Transition (Negative Test)**: Add a scroll to `Master scroll book (empty)` turning it into `Master scroll book` (or emptying it completely) → Container ID canonicalization ensures zero phantom profit or loss for the book item itself.
- [ ] **Dizana's Max Cape Support**: Wear Dizana's max cape with ammo loaded in the quiver container → Detects the max cape as a Dizana's quiver and properly reconciles consumed ammo.

---

## 17. Herb Sack (Open & Closed)
- [ ] **Direct Ground Pickup into Open Herb Sack**: Carry an open herb sack (`Open herb sack`) and pick up a grimy herb from the ground (e.g. Grimy ranarr weed, Grimy snapdragon) → Herb enters the sack directly without occupying an inventory slot; profit is immediately credited to session profit and gold drop overlay triggers.
- [ ] **Herbiboar & Farming Chat Harvest**: Harvest grimy herbs from Herbiboar or farming patches while carrying an open herb sack → Chat message confirms herbs stored in sack; profit is credited on the next tick.
- [ ] **Filling / Emptying Herb Sack (Negative Test)**: Click "Fill" or "Empty" on an herb sack, or use grimy herbs on the sack → Rebaseline triggers; zero false profit or loss is recorded.
- [ ] **Toggling Open / Closed (Negative Test)**: Click "Open" or "Close" on an herb sack in inventory → Normalized item ID ensures zero profit or loss recorded.

---

## 18. Fish Barrel & Fish Sack Barrel
- [ ] **Catching Fish into Open Fish Barrel**: Fish with an open Fish Barrel (`Open fish barrel` or `Open fish sack barrel`) in inventory or worn on back → Fishing catch messages auto-deposit raw fish into the barrel; profit is credited immediately to session profit.
- [ ] **Emptying Fish Barrel (Negative Test)**: Right-click "Empty" on a Fish Barrel at a bank or in inventory → Rebaseline ensures no duplicate profit when dumping fish out.
- [ ] **Toggling Open / Closed (Negative Test)**: Click "Open" or "Close" on a Fish Barrel in inventory or worn slot → Normalized item ID ensures zero profit or loss recorded.

---

## 19. Seed Box (Open & Closed)
- [ ] **Direct Ground Pickup into Open Seed Box**: Carry an open seed box (`Open seed box`) and pick up seeds from the ground (e.g. Ranarr seed, Snapdragon seed) → Seeds enter the box directly; profit is immediately credited to session profit.
- [ ] **Master Farmer Pickpocketing**: Pickpocket the Master Farmer with an open seed box in inventory → Seeds sent straight to the box via chat messages are credited to session profit.
- [ ] **Filling / Emptying Seed Box (Negative Test)**: Click "Fill" or "Empty" on the seed box, or use seeds on the box → Rebaseline triggers; zero false profit or loss is recorded.
- [ ] **Toggling Open / Closed (Negative Test)**: Click "Open" or "Close" on a seed box in inventory → Normalized item ID ensures zero profit or loss recorded.

---

## 20. PvP Loot Keys & Wilderness Chest
- [ ] **Opening Wilderness Loot Chest**: Use a PvP Loot Key on the chest in Ferox Enclave or Edgeville → Loot interface opens; item contents in key containers (558-562) are extracted and credited to session profit once.
- [ ] **Loot Chest Interface Suppression (Negative Test)**: Withdraw items or coins from the PvP Loot Chest interface to inventory or bank → Suppressed interface ensures items are not double-counted as profit and closing the chest does not register false losses.

---

## 21. Ash Sanctifier & Bonecrusher
- [ ] **Charging Ash Sanctifier with Death Runes (Negative Test)**: Charge an Ash Sanctifier with Death runes from inventory → Varbit charges increase while loose Death runes decrease by the same amount; net change is 0 gp profit/loss.
- [ ] **Ash Scattering in Combat**: Kill an ash-dropping monster with an Ash Sanctifier in inventory → Sanctifier consumes 1 Death rune charge to scatter ashes into Prayer XP; 1 Death rune is recorded as a supply expense; scattered ashes are not credited as physical loot.
- [ ] **Bonecrusher Prayer Conversion (Negative Test)**: Kill a bone-dropping monster with a Bonecrusher or Bonecrusher necklace in inventory/worn → Bones crushed directly into Prayer XP do not enter physical inventory; no physical loot is falsely recorded.


