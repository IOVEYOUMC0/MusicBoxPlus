package com.huidu.musicboxplus.module.jukebox;

// Checked failure raised while a jukebox playlist is being assembled from its
// stored container reference, so the caller can surface a readable error instead
// of letting a broken reference surface as a generic exception deep in the stack.
public class JukeboxPlaylistInitException extends Exception {

    private static final long serialVersionUID = 1L;

    public JukeboxPlaylistInitException(String message) {
        super(message);
    }

    public JukeboxPlaylistInitException(String message, Throwable cause) {
        super(message, cause);
    }
}