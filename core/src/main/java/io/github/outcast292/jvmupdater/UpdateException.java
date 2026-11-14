package io.github.outcast292.jvmupdater;

import java.io.IOException;

/** A release was refused: bad signature, wrong hash, invalid manifest and so on. */
public class UpdateException extends IOException {

    public UpdateException(String message) {
        super(message);
    }

    public UpdateException(String message, Throwable cause) {
        super(message, cause);
    }
}
