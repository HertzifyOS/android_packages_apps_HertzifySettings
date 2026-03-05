package com.hertzify.settings.preferences;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;

import com.android.settings.R;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

public class PifDataPreference extends Preference {

    private static final String TAG = "PifDataPref";

    private ActivityResultLauncher<Intent> mFilePickerLauncher;

    public PifDataPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        setLayoutResource(R.layout.pref_with_delete);
    }

    public void setFilePickerLauncher(ActivityResultLauncher<Intent> launcher) {
        this.mFilePickerLauncher = launcher;
    }

    @Override
    public void onBindViewHolder(PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);

        final Context ctx = getContext();
        final ContentResolver cr = ctx.getContentResolver();

        TextView title = (TextView) holder.findViewById(R.id.title);
        TextView summary = (TextView) holder.findViewById(R.id.summary);
        ImageButton deleteButton = (ImageButton) holder.findViewById(R.id.delete_button);

        title.setText(getTitle());

        boolean hasData = Settings.Secure.getString(
                cr, Settings.Secure.PIF_DATA) != null;

        summary.setText(ctx.getString(
                hasData ? R.string.pif_data_loaded : R.string.pif_data_summary));

        deleteButton.setVisibility(hasData ? View.VISIBLE : View.GONE);
        deleteButton.setEnabled(hasData);

        holder.itemView.setOnClickListener(v -> {
            if (mFilePickerLauncher != null) {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.setType("application/json");
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                mFilePickerLauncher.launch(intent);
            }
        });

        deleteButton.setOnClickListener(v -> {
            Settings.Secure.putString(cr, Settings.Secure.PIF_DATA, null);
            Toast.makeText(ctx, ctx.getString(R.string.pif_data_cleared),
                    Toast.LENGTH_SHORT).show();
            notifyChanged();
        });
    }

    public void handleFileSelected(Uri uri) {
        final Context ctx = getContext();
        final ContentResolver cr = ctx.getContentResolver();

        if (uri == null) {
            showToast(R.string.pif_data_invalid_file);
            return;
        }

        String type = cr.getType(uri);
        boolean isJsonMime = "application/json".equals(type);
        boolean hasJsonExt = uri.getPath() != null && uri.getPath().toLowerCase().endsWith(".json");

        if (!isJsonMime && !hasJsonExt) {
            showToast(R.string.pif_data_invalid_file);
            return;
        }

        try (InputStream inputStream = cr.openInputStream(uri);
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(inputStream, java.nio.charset.StandardCharsets.UTF_8))) {

            StringBuilder jsonContent = new StringBuilder();
            String line;

            while ((line = reader.readLine()) != null) {
                jsonContent.append(line).append('\n');
            }

            String json = jsonContent.toString();

            Settings.Secure.putString(cr, Settings.Secure.PIF_DATA, json);

            Toast.makeText(ctx,
                    ctx.getString(R.string.pif_data_loaded),
                    Toast.LENGTH_SHORT).show();

            notifyChanged();

        } catch (IOException e) {
            Log.e(TAG, "Failed to read JSON file", e);
            showToast(R.string.pif_data_failed);
        }
    }

    private void showToast(int resId) {
        Toast.makeText(getContext(), resId, Toast.LENGTH_SHORT).show();
    }
}