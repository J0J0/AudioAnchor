package com.prangesoftwaresolutions.audioanchor.helpers;

import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.icu.text.Collator;
import android.icu.text.RuleBasedCollator;
import android.net.Uri;
import android.util.Pair;
import androidx.preference.PreferenceManager;
import android.widget.Toast;

import com.prangesoftwaresolutions.audioanchor.R;
import com.prangesoftwaresolutions.audioanchor.data.AnchorContract;
import com.prangesoftwaresolutions.audioanchor.listeners.SynchronizationStateListener;
import com.prangesoftwaresolutions.audioanchor.models.Album;
import com.prangesoftwaresolutions.audioanchor.models.AudioFile;
import com.prangesoftwaresolutions.audioanchor.models.Directory;

import java.io.File;
import java.io.FilenameFilter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

public class Synchronizer {
    private final Context mContext;
    private final SharedPreferences mPrefManager;
    private SynchronizationStateListener mListener = null;

    public Synchronizer(Context context) {
        mContext = context;
        mPrefManager = PreferenceManager.getDefaultSharedPreferences(context);
    }

    public void setListener(SynchronizationStateListener listener) {
        mListener = listener;
    }

    /*
     * Insert a new directory to the database and add its contained albums and audiofiles accordingly
     */
    public void addDirectory(Directory directory) {
        directory.insertIntoDB(mContext);
        updateAlbumTable(directory);
    }

    /*
     * For each directory in the database update albums according to current status of the file system
     */
    public void updateDBTables() {
        ArrayList<Directory> directories = Directory.getDirectories(mContext);
        for (Directory directory : directories) {
            updateAlbumTable(directory);
        }
    }

    /*
     * Update the album database table if the list of directories in the selected directory does not
     * match the album table entries
     */
    private void updateAlbumTable(Directory directory) {
        // Filter to get all subdirectories in a directory
        boolean showHidden = mPrefManager.getBoolean(mContext.getString(R.string.settings_show_hidden_key), Boolean.getBoolean(mContext.getString(R.string.settings_show_hidden_default)));
        FilenameFilter filter = (dir, filename) -> {
            File sel = new File(dir, filename);
            // Only list files that are readable and directories and not hidden unless corresponding option is set
            return sel.canRead() && sel.isDirectory() && (showHidden || !sel.getName().startsWith("."));
        };

        ArrayList<String> newAlbumPaths = new ArrayList<>();
        File dir = new File(directory.getPath());
        if (dir.exists() && dir.isDirectory()) {
            if (directory.getType() == Directory.Type.PARENT_DIR) {
                // Add all subdirectories if directory is a parent directory
                String[] subDirArr = dir.list(filter);
                for (String subDirString : subDirArr) {
                    String absolutePath = new File(directory.getPath(), subDirString).getAbsolutePath();
                    newAlbumPaths.add(absolutePath);
                }
            } else if (dir.canRead() && (showHidden || !dir.getName().startsWith("."))) {
                // Add directory if it is a subdirectory
                newAlbumPaths.add(dir.getAbsolutePath());
            }
        }

        LinkedHashMap<String, Album> oldAlbumPaths = new LinkedHashMap<>();
        ArrayList<Album> albums = Album.getAllAlbumsInDirectory(mContext, directory.getID());
        for (Album album : albums) {
            String path = album.getPath();
            oldAlbumPaths.put(path, album);
        }

        // Insert new albums into the database
        for (String newAlbumPath : newAlbumPaths) {
            long id;
            if (!oldAlbumPaths.containsKey(newAlbumPath)) {
                String albumTitle = new File(newAlbumPath).getName();
                Album album = new Album(albumTitle, directory);
                id = album.insertIntoDB(mContext);
            } else {
                Album album = oldAlbumPaths.get(newAlbumPath);
                id = album.getID();

                // Update cover path
                String oldCoverPath = album.getRelativeCoverPath();
                String newCoverPath = album.updateAlbumCover();
                if (newCoverPath != null && (oldCoverPath == null || !oldCoverPath.equals(newCoverPath))) {
                    album.updateInDB(mContext);
                }

                oldAlbumPaths.remove(newAlbumPath);
            }
            updateAudioFileTable(newAlbumPath, id);
        }

        // Delete missing or hidden directories from the database
        boolean keepDeleted = mPrefManager.getBoolean(mContext.getString(R.string.settings_keep_deleted_key), Boolean.getBoolean(mContext.getString(R.string.settings_keep_deleted_default)));
        for (String path : oldAlbumPaths.keySet()) {
            String directoryName = new File(path).getName();
            if (!keepDeleted || (!showHidden && directoryName.startsWith("."))) {
                // Delete the album in the albums table
                long id = oldAlbumPaths.get(path).getID();
                Uri uri = ContentUris.withAppendedId(AnchorContract.AlbumEntry.CONTENT_URI, id);
                mContext.getContentResolver().delete(uri, null, null);
            }
        }
        if (mListener != null) {
            mListener.onSynchronizationFinished();
        }
    }


    /*
     * Update the audiofiles table if the list of audio files in the album directory does not
     * match the audiofiles table entries
     */
     private void updateAudioFileTable(String albumPath, long albumId) {
        // Get all audio files in the album.
        FilenameFilter filter = (dir, filename) -> {
            File sel = new File(dir, filename);

            // Don't show files starting with a dot (hidden files) unless the option is set
            boolean showHidden = mPrefManager.getBoolean(mContext.getString(R.string.settings_show_hidden_key), Boolean.getBoolean(mContext.getString(R.string.settings_show_hidden_default)));
            if (!showHidden && sel.getName().startsWith(".")) {
                return false;
            }

            // Only list files that are readable and audio files
            String[] supportedFormats = {".mp3", ".wma", ".ogg", ".wav", ".flac", ".m4a", ".m4b", ".aac", ".3gp", ".gsm", ".mid", ".mkv", ".opus"};
            for (String format : supportedFormats) {
                if (sel.getName().endsWith(format)) return true;
            }
            return false;
        };

        // Get all files in the album directory.
        String[] fileList;
        File albumDir = new File(albumPath);

        if (albumDir.exists()) {
            fileList = albumDir.list(filter);
        } else {
            fileList = new String[]{};
        }

        if (fileList == null) return;

        ArrayList<AudioFile> audioFiles = AudioFile.getAllAudioFilesInAlbum(mContext, albumId, null);
        Map<String, AudioFile> audioTitles = new HashMap<>();
        for (AudioFile audioFile : audioFiles) {
            audioTitles.put(audioFile.getTitle(), audioFile);
        }

        RuleBasedCollator collator = (RuleBasedCollator) Collator.getInstance(); // gets collator for default (i.e. host) locale
        collator.setNumericCollation(true);
        SortedMap<String, Integer> allFiles = new TreeMap<>(collator);
        for (String f : fileList) allFiles.put(f, 1);
        for (String f : audioTitles.keySet()) allFiles.merge(f, 2, Integer::sum);

        boolean keepDeleted = mPrefManager.getBoolean(mContext.getString(R.string.settings_keep_deleted_key), Boolean.getBoolean(mContext.getString(R.string.settings_keep_deleted_default)));
        boolean showHidden = mPrefManager.getBoolean(mContext.getString(R.string.settings_show_hidden_key), Boolean.getBoolean(mContext.getString(R.string.settings_show_hidden_default)));
        String errorString = null;
        Iterator<Map.Entry<String, Integer>> it = allFiles.entrySet().iterator();
        int sortIndex = 1;
        while (it.hasNext()) {
            Map.Entry<String, Integer> entry = it.next();
            String audioFileName = entry.getKey();
            int audioFileOrigin  = entry.getValue();

            if (audioFileOrigin == 1) { // file is physically present, but not in database
                AudioFile audioFile = new AudioFile(mContext, audioFileName, albumId, sortIndex);
                long id = audioFile.insertIntoDB(mContext);
                if (id == -1) errorString = albumPath + "/" + audioFileName;
            } else { // file in database
                boolean dbEntryDeleted = false;

                if (audioFileOrigin == 2) { // in database, but not in the album directory
                    if (errorString == null) { // skip removal if some insert failed
                        if (!keepDeleted || (!showHidden && audioFileName.startsWith("."))) {
                            long id = audioTitles.get(audioFileName).getID();
                            Uri uri = ContentUris.withAppendedId(AnchorContract.AudioEntry.CONTENT_URI, id);
                            mContext.getContentResolver().delete(uri, null, null);
                            dbEntryDeleted = true;
                        }
                    }
                }

                if (!dbEntryDeleted) {
                    // Update the sort index in the database if necessary
                    AudioFile existingFile = audioTitles.get(audioFileName);
                    if (existingFile.getSortIndex() != sortIndex) {
                        Uri updateUri = ContentUris.withAppendedId(AnchorContract.AudioEntry.CONTENT_URI, existingFile.getID());
                        ContentValues values = new ContentValues();
                        values.put(AnchorContract.AudioEntry.COLUMN_SORT_INDEX, sortIndex);
                        mContext.getContentResolver().update(updateUri, values, null, null);
                    }
                }
            }

            sortIndex++;
        }
        if (errorString != null) {
            errorString = mContext.getResources().getString(R.string.audio_file_error, errorString);
            Toast.makeText(mContext.getApplicationContext(), errorString, Toast.LENGTH_SHORT).show();
        }
    }
}
