/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Written by lavinhoque33, 2026-11-26.
 */
package org.chromium.net;

import java.io.IOException;

/**
 * Stands in for Cronet's own class of this name, which the extension never links: it recognises the real one by name
 * only, so a test needs nothing more than a class of this name and its subclasses.
 */
public class NetworkException extends IOException {
    public NetworkException(String message) {
        super(message);
    }

    /** Cronet's QUIC errors are a subclass of it. */
    public static final class Quic extends NetworkException {
        public Quic(String message) {
            super(message);
        }
    }
}
