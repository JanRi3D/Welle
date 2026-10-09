package me.ri3d.welle;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.List;

/**
 * Read-only artwork for dashboards on the head unit (see {@link RadioService#ACTION_STATE}):
 * {@code content://me.ri3d.welle.art/slide} is the last DAB slideshow picture and
 * {@code content://me.ri3d.welle.art/logo/<station id>} a station logo. Nothing else is served;
 * logo file names are sanitised by {@code LogoStore}, so a path cannot leave the logo folder.
 */
public final class ArtProvider extends ContentProvider {
    static final String BASE = "content://me.ri3d.welle.art/";

    /** Where RadioService keeps the current slideshow picture. */
    static File slideFile(Context c) {
        return new File(c.getCacheDir(), "slide.img");
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("read-only");
        List<String> p = uri.getPathSegments();
        File f = null;
        if (p.size() == 1 && "slide".equals(p.get(0))) f = slideFile(getContext());
        else if (p.size() == 2 && "logo".equals(p.get(0))) f = App.of(getContext()).logos.fileOf(p.get(1));
        if (f == null || !f.isFile()) throw new FileNotFoundException(uri.toString());
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public String getType(Uri uri) {
        return "image/*";
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sortOrder) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("read-only");
    }

    @Override
    public int delete(Uri uri, String selection, String[] args) {
        throw new UnsupportedOperationException("read-only");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] args) {
        throw new UnsupportedOperationException("read-only");
    }
}
