package com.rspsi.openglprototype;

import com.jagex.Client;
import com.jagex.chunk.Chunk;

/**
 * Read-only adapter boundary between the working editor scene and the future
 * GPU scene uploader. Keeping this thin lets us preserve the current cache,
 * editing and save systems while replacing only rendering.
 */
public final class CurrentSceneBridge {
    private CurrentSceneBridge() {
    }

    public static String describe(Client client) {
        if (client == null) {
            return "client=null";
        }

        StringBuilder out = new StringBuilder("chunks=").append(client.chunks.size());
        for (int i = 0; i < client.chunks.size(); i++) {
            Chunk chunk = client.chunks.get(i);
            out.append(" #").append(i)
                    .append('(').append(chunk.offsetX).append(',').append(chunk.offsetY).append(')');
        }
        return out.toString();
    }
}
