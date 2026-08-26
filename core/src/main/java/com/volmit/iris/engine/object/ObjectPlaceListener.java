package com.volmit.iris.engine.object;

import org.bukkit.block.data.BlockData;

/**
 * Per-block callback of {@code IrisObject.place}. Primitive coordinates: the
 * old BiConsumer&lt;BlockPosition, BlockData&gt; allocated a BlockPosition per
 * placed block, and the only production listener (MantleObjectComponent's
 * object writer) immediately unboxed it back into setData(x, y, z).
 */
@FunctionalInterface
public interface ObjectPlaceListener {
    void onPlace(int x, int y, int z, BlockData data);
}
