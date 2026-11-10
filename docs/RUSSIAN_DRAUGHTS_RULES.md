# Classic Russian Draughts — Implemented Rules

This project implements **classic Russian Draughts on an 8×8 board**. It does not implement International Draughts (10×10) or the separate tournament sub-variant sometimes labelled "Shashki-64".

## Board and start
- 8×8 board; play takes place only on the 32 dark squares.
- Each side starts with 12 men on the three nearest rows.
- White moves first.
- White men advance toward decreasing row indexes in this app's board coordinates; black men advance toward increasing row indexes.

## Men
- A man moves one diagonal square forward to an empty square.
- A man captures one adjacent enemy man or king diagonally and lands on the next square beyond it.
- A man may capture in all four diagonal directions, including backwards.
- A man cannot move quietly backwards.

## Kings
- A king is a flying king: it moves any unobstructed distance along a diagonal.
- To capture, the king must be on a diagonal with an enemy piece and at least one empty square beyond that piece.
- The king may land on any empty square beyond the captured piece along the same diagonal, before the next occupied square.
- Own pieces block movement and capture paths.

## Mandatory capture and multi-capture
- If any piece of the side to move can capture, quiet moves are not legal.
- A capture sequence is one complete turn.
- After each capture, the same moving piece must continue if another capture is available.
- The moving piece may not capture the same enemy piece more than once in one sequence.
- A square vacated earlier in the same sequence may be visited again if it is otherwise empty.

## Captured pieces and the Turkish strike
- Captured pieces remain physically on the board until the whole multiple-capture sequence is complete.
- They therefore continue to block diagonal paths and cannot be captured a second time in that same sequence.
- After the sequence is complete, all captured pieces are removed.
- This preserves the Russian Draughts "Turkish strike" behavior. Do not remove a captured piece immediately during recursive move generation.

## Promotion
- A man reaching its final row on a quiet move becomes a king at the end of that move.
- If a man reaches its final row during a capture sequence and another capture is available, it immediately continues the same turn with king movement rules.
- If it reaches the final row during a capture sequence but has no further capture, it finishes the turn as a king.

## Capture choice
- In the **classic Russian Draughts** rules published by the Russian Draughts Federation, when several capture directions/sequences are available, the player may choose; the choice is not forced by the number or rank of captured pieces.
- This is distinct from the separately described "Shashki-64" tournament system, which has a majority-capture rule. This implementation intentionally follows the classic Russian variant.

## Scope not yet implemented
The current rules engine covers board movement, complete capture sequences, compulsory capture, promotion, and applying legal moves. Tournament draw regulations (repetition, move-count endings, time controls, match claims) are not yet implemented.

## Sources
1. Russian Draughts Federation, "Русские шашки", rules sections on men/kings, captures, sequential capture, Turkish strike, promotion and capture choice: https://shashki.ru/variations/draughts64/
2. Russian Ministry of Sport, Order No. 201 of 17 March 2022, "Правила вида спорта «шашки»", Russian Draughts provisions (including 6.1.4–6.1.5): https://base.garant.ru/403725680/

Source checked on 11 November 2026.
