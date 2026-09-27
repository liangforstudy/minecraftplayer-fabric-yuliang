package dev.yuliang.zymbot.core.api;

import java.util.UUID;

/**
 * Whose civfabric grave this is, as the block entity data the server sends every client says
 * ({@code owner} name and {@code owner_id} UUID — the grave renders its owner's head, so the client
 * gets both). {@code id} may be null (civfabric leaves it out when unknown).
 */
public record GraveOwner(String name, UUID id) {
    /**
     * civfabric's own test (GraveBlockEntity.isOwner): the same UUID, or the same name. Its right-click
     * gives the grave back to that player and opens a chest screen for anyone else.
     */
    public boolean is(UUID selfId, String selfName) {
        if (id != null && id.equals(selfId)) return true;
        return name != null && !name.isEmpty() && name.equals(selfName);
    }

    public String label() { return name == null || name.isEmpty() ? "someone" : name; }
}
