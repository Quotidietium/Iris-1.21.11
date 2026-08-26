package bench;

import com.volmit.iris.engine.object.IrisObject;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.HashMap;

/**
 * Round 13 equivalence proof for the .iob write path refactor:
 *
 *  A. Byte-for-byte: the refactored IrisObject.write(OutputStream) vs a
 *     verbatim copy of the pre-R13 algorithm (O(n^2) indexOf per block) on
 *     identical objects — the format is a red line, the helper swap must not
 *     change a single byte.
 *  B. Round-trip: write -> read back yields the same block count and the same
 *     material at sampled positions.
 */
public class VerifyObjectIOB {
    public static void main(String[] args) throws Exception {
        com.volmit.iris.core.IrisSettings.settings = new com.volmit.iris.core.IrisSettings();
        com.volmit.iris.Iris.compat = new com.volmit.iris.engine.object.IrisCompat();

        IrisObject o = new IrisObject(9, 13, 9);
        BlockData log = Material.OAK_LOG.createBlockData();
        BlockData leaf = Material.OAK_LEAVES.createBlockData();
        BlockData stone = Material.STONE.createBlockData();
        java.util.Random r = new java.util.Random(4242);
        for (int x = 0; x < 9; x++)
            for (int y = 0; y < 13; y++)
                for (int z = 0; z < 9; z++)
                    if (r.nextInt(3) > 0)
                        o.setUnsigned(x, y, z, new BlockData[]{log, leaf, stone, leaf, log}[r.nextInt(5)]);

        byte[] fresh;
        try (ByteArrayOutputStream bo = new ByteArrayOutputStream()) {
            o.write(bo);
            fresh = bo.toByteArray();
        }

        byte[] legacy = legacyWrite(o);
        check(fresh.length == legacy.length, "length " + fresh.length + " vs " + legacy.length);
        for (int i = 0; i < fresh.length; i++) {
            check(fresh[i] == legacy[i], "byte mismatch at " + i + ": " + (fresh[i] & 0xff) + " vs " + (legacy[i] & 0xff));
        }
        System.out.println("A: byte-for-byte identical to pre-R13 algorithm (" + fresh.length + " bytes)");

        IrisObject back = new IrisObject(0, 0, 0);
        try { back.read(new java.io.ByteArrayInputStream(fresh)); } catch (Throwable t) { throw new RuntimeException(t); }
        check(back.getW() == o.getW() && back.getH() == o.getH() && back.getD() == o.getD(), "dimensions");
        int n = 0;
        for (int x = 0; x < 9; x++)
            for (int y = 0; y < 13; y++)
                for (int z = 0; z < 9; z++) {
                    BlockData a = o.getBlocks().get(new org.bukkit.util.BlockVector(x, y, z));
                    BlockData b = back.getBlocks().get(new org.bukkit.util.BlockVector(x, y, z));
                    check((a == null) == (b == null), "presence mismatch at " + x + "," + y + "," + z);
                    if (a != null) {
                        check(a.getAsString().equals(b.getAsString()), "value mismatch at " + x + "," + y + "," + z);
                        n++;
                    }
                }
        System.out.println("B: round-trip verified (" + n + " blocks)");
        System.out.println("VerifyObjectIOB: PASS");
    }

    /** Verbatim pre-R13 algorithm (indexOf per block). */
    private static byte[] legacyWrite(IrisObject o) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(bo);
        dos.writeInt(o.getW());
        dos.writeInt(o.getH());
        dos.writeInt(o.getD());
        dos.writeUTF("Iris V2 IOB;");
        com.volmit.iris.util.collection.KList<String> palette = new com.volmit.iris.util.collection.KList<>();
        for (BlockData i : o.getBlocks().values()) {
            palette.addIfMissing(i.getAsString());
        }
        dos.writeShort(palette.size());
        for (String i : palette) {
            dos.writeUTF(i);
        }
        dos.writeInt(o.getBlocks().size());
        // replicate VectorMap chunk-then-relative traversal via the object's own iterator
        for (var entry : o.getBlocks()) {
            var i = entry.getKey();
            dos.writeShort(i.getBlockX());
            dos.writeShort(i.getBlockY());
            dos.writeShort(i.getBlockZ());
            dos.writeShort(palette.indexOf(entry.getValue().getAsString()));
        }
        dos.writeInt(0); // no states in this fixture
        dos.flush();
        return bo.toByteArray();
    }

    private static void check(boolean c, String msg) {
        if (!c) throw new IllegalStateException("FAIL: " + msg);
    }
}
