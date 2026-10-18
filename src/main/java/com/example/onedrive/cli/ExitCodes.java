package com.example.onedrive.cli;

/** Process exit codes for the CLI (documented in README and --help). */
public final class ExitCodes {
    public static final int OK = 0;
    public static final int USAGE = 1;
    public static final int AUTH_CONFIG = 2;
    public static final int GRAPH = 3;
    public static final int IO = 4;

    private ExitCodes() {}
}
