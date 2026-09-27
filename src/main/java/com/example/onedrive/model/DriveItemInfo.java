package com.example.onedrive.model;

/**
 * Lightweight view of a DriveItem returned by Microsoft Graph.
 */
public record DriveItemInfo(
        String id,
        String name,
        String webUrl,
        Long size,
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

    @Override
    public String toString() {
        String sizePart = size == null ? "-" : String.valueOf(size);
        return String.format("[%s] %s  size=%s  id=%s", kind(), name, sizePart, id);
    }
}
