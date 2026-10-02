package de.kevloe.vibecloud.api.event.events;

import de.kevloe.vibecloud.api.node.NodeInfo;

/** Ein Wrapper hat sich angemeldet und der Control-Stream steht. */
public record NodeConnectedEvent(NodeInfo node) {
}
