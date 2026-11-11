# Classic Russian Draughts — Implemented Rules

This project implements **classic Russian Draughts on an 8×8 board**. It does not implement International Draughts (10×10) or the separate tournament sub-variant sometimes labelled "Shashki-64".

## Summary of Verified Rules
1. 8×8 board; play takes place only on the 32 dark squares (`(row + col) % 2 == 1`).
2. Each side starts with 12 men on the three nearest rows. White moves first.
3. Men move one diagonal square forward on quiet moves and capture in all four diagonal directions.
4. Kings are flying kings: they move and capture along unobstructed diagonals. When capturing, if any landing square beyond the victim allows a continuation capture, the king must land on a square that continues the capture.
5. Captured pieces remain on the board until the entire multi-capture sequence finishes (Turkish strike / Турецкий удар). They block diagonal rays and cannot be captured twice.
6. A man reaching the promotion rank during a capture crowns immediately and continues the same turn as a flying king.
7. When multiple capture routes exist, the player has free choice among complete capture sequences.
