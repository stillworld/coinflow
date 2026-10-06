# Coin Flow (Beta)

Coin Flow is a live profit and GP/hour tracker for RuneLite. It watches your inventory and equipment changes tick-by-tick to calculate what you're actually making in real time whether you're bossing, doing slayer, skilling, or running clues.

> **Note:** Coin Flow is currently in **beta**. While core tracking and common activities are well tested, you might occasionally run into edge cases with specific items, shops, or minigames. If something doesn't look right, please open an issue!

<br>

![overlay example](<Screenshot 2026-09-30 at 5.00.43 PM.png>)

## Features

- **Live Overlay & GP/hr:** Lightweight on-screen display showing your net profit, current GP/hr, session timer, and goal progress. Switch to a minimal single-line `gp/hr` style with an optional transparent background for an out-of-the-way display.
- **Net Profit & Supply Tracking:** Optionally tracks consumed supplies (food bites, potion doses, ammo, runes, etc.) so you see actual profit rather than just raw loot value.
- **Floating Gold Drops:** Floating "+GP" drops (like XP drops) when you get loot or make money. Can appear overhead or in the top-right corner.
- **Sidebar Panel:** Side panel showing your session breakdown, per-item gains and losses with current GE prices, and a quick reset button.
- **Goal Mode:** Set a target GP goal (e.g. `10m`, `Bond`) with a progress bar and real-time ETA based on your current rate.
- **Interface Aware:** Automatically pauses tracking when you open the bank, Grand Exchange, shops, or trade screens so banking doesn't mess up your stats.

## Configuration

You can configure Coin Flow in the standard RuneLite plugin settings:
- **Display:** Toggle the overlay, pick a detailed or single-line overlay style, show or hide the overlay background, and configure side panel compact mode and gold drop styles/positions.
- **Thresholds:** Set a minimum GP value for floating gold drops so small items don't clutter the screen.
- **Supplies:** Turn supply cost deduction on or off depending on whether you want gross or net profit.
- **Ignored Items:** Add comma-separated items you don't want tracked (e.g. `Vial, Jug, Bones`).
- **AFK / Idle:** Configure how idle time affects your calculated GP/hr.

<br> 

![side panel example](<Screenshot 2026-09-30 at 5.10.08 PM-1.png>)

## Feedback & Bug Reports

If something doesn't look right or an item isn't tracked properly, please open an issue on GitHub with:
1. What activity you were doing
2. What item or interaction caused the issue
3. What the expected vs. actual result was

## License

[BSD 2-Clause License](LICENSE)