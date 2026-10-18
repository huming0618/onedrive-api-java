package com.example.onedrive.model;

/**
 * Lightweight view of a DriveItem returned by Microsoft Graph.
 */
public record DriveItemInfo(
        String id,
        String name,
        String path,
        String webUrl,
        Long size,
        String lastModified,
        boolean folder,
        boolean file) {

    public String kind() {
        if (folder) {
            return "folder";
        }
        if (file) {
            return "file";
        }
        return "item";
    }

    /** Relative path for display; falls back to name. */
    public String displayPath() {
        if (path != null && !path.isBlank()) {
            return path;
        }
        return name == null ? "" : name;
    }

    @Override
    public String toString() {
        String sizePart = size == null ? "-" : String.valueOf(size);
        String p = displayPath();
        String lm = lastModified == null ? "-" : lastModified;
        return String.format("[%s] %s  size=%s  id=%s  modified=%s", kind(), p, sizePart, id, lm);
    }
}
