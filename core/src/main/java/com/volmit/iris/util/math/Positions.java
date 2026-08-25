/*
 * Iris is a World Generator for Minecraft Bukkit Servers
 * Copyright (c) 2022 Arcane Arts (Volmit Software)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.volmit.iris.util.math;

/**
 * 21+21+21-bit block-coordinate packing (range ±1,048,575 per axis; bit 63
 * stays free). Companion unpacks sign-extend by shifting the field to the
 * int top and arithmetic-shifting back down. Machine-verified by
 * {@code bench.VerifyCaveSet} (8.1M signed-combination roundtrip).
 */
public final class Positions {
    private Positions() {
    }

    public static long pack(int x, int y, int z) {
        return ((x & 0x1FFFFFL) << 42) | ((y & 0x1FFFFFL) << 21) | (z & 0x1FFFFFL);
    }

    public static int unpackX(long p) {
        return ((int) (p >> 42)) << 11 >> 11;
    }

    public static int unpackY(long p) {
        return ((int) (p >> 21)) << 11 >> 11;
    }

    public static int unpackZ(long p) {
        return (int) p << 11 >> 11;
    }
}
