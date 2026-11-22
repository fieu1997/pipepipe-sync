package org.schabi.newpipe.settings;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.preference.Preference;

import org.schabi.newpipe.App;
import org.schabi.newpipe.R;
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper;
import org.schabi.newpipe.util.LoginSessions;
import org.schabi.newpipe.views.YouTubeLoginWebViewActivity;
import org.schabi.newpipe.youtube.LocalDomPoTokenProvider;
import org.schabi.newpipe.youtube.YouTubeAccountSyncManager;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.disposables.CompositeDisposable;

public class YouTubeAccountSettingsFragment extends BaseAccountSettingsFragment {
    private final CompositeDisposable disposables = new CompositeDisposable();

    private Preference syncAllPref;
    private Preference syncSubscriptionsPref;
    private Preference syncHistoryPref;
    private Preference autoSyncPref;
    private Preference reportHistoryPref;

    @Override
    public void onCreatePreferences(final Bundle savedInstanceState, final String rootKey) {
        super.onCreatePreferences(savedInstanceState, rootKey);
        setupSyncPreferences();
        updateSyncPreferencesState();
    }

    private void setupSyncPreferences() {
        syncAllPref = findPreference(getString(R.string.youtube_sync_all_key));
        syncSubscriptionsPref = findPreference(getString(R.string.youtube_sync_subscriptions_key));
        syncHistoryPref = findPreference(getString(R.string.youtube_sync_history_key));
        autoSyncPref = findPreference(getString(R.string.youtube_auto_sync_key));
        reportHistoryPref = findPreference(getString(R.string.youtube_report_history_key));

        if (syncAllPref != null) {
            syncAllPref.setOnPreferenceClickListener(preference -> {
                runFullSync();
                return true;
            });
        }
        if (syncSubscriptionsPref != null) {
            syncSubscriptionsPref.setOnPreferenceClickListener(preference -> {
                runSubscriptionsSync();
                return true;
            });
        }
        if (syncHistoryPref != null) {
            syncHistoryPref.setOnPreferenceClickListener(preference -> {
                runHistorySync();
                return true;
            });
        }
    }

    private void updateSyncPreferencesState() {
        final boolean loggedIn = !defaultPreferences.getString(getCookiesKey(), "").isEmpty();
        if (syncAllPref != null) {
            syncAllPref.setEnabled(loggedIn);
        }
        if (syncSubscriptionsPref != null) {
            syncSubscriptionsPref.setEnabled(loggedIn);
        }
        if (syncHistoryPref != null) {
            syncHistoryPref.setEnabled(loggedIn);
        }
        if (autoSyncPref != null) {
            autoSyncPref.setEnabled(loggedIn);
        }
        if (reportHistoryPref != null) {
            reportHistoryPref.setEnabled(loggedIn);
        }
    }

    private void setSyncButtonsBusy(final boolean busy) {
        final boolean enabled = !busy && !defaultPreferences.getString(getCookiesKey(), "").isEmpty();
        if (syncAllPref != null) {
            syncAllPref.setEnabled(enabled);
        }
        if (syncSubscriptionsPref != null) {
            syncSubscriptionsPref.setEnabled(enabled);
        }
        if (syncHistoryPref != null) {
            syncHistoryPref.setEnabled(enabled);
        }
    }

    private void runFullSync() {
        setSyncButtonsBusy(true);
        Toast.makeText(requireContext(), R.string.youtube_sync_in_progress, Toast.LENGTH_SHORT).show();
        disposables.add(
                YouTubeAccountSyncManager.syncAll(requireContext())
                        .observeOn(AndroidSchedulers.mainThread())
                        .doFinally(() -> setSyncButtonsBusy(false))
                        .subscribe(
                                result -> {
                                    if (getContext() != null) {
                                        Toast.makeText(
                                                getContext(),
                                                getString(
                                                        R.string.youtube_sync_all_success,
                                                        result.getSubscriptions().getTotalFetched(),
                                                        result.getSubscriptions().getAddedCount(),
                                                        result.getHistory().getAddedCount()
                                                ),
                                                Toast.LENGTH_LONG
                                        ).show();
                                    }
                                },
                                error -> {
                                    if (getContext() != null) {
                                        Toast.makeText(
                                                getContext(),
                                                getString(R.string.youtube_sync_failed,
                                                        error.getMessage() != null ? error.getMessage() : ""),
                                                Toast.LENGTH_LONG
                                        ).show();
                                    }
                                }
                        )
        );
    }

    private void runSubscriptionsSync() {
        setSyncButtonsBusy(true);
        Toast.makeText(requireContext(), R.string.youtube_sync_in_progress, Toast.LENGTH_SHORT).show();
        disposables.add(
                YouTubeAccountSyncManager.syncSubscriptions(requireContext())
                        .observeOn(AndroidSchedulers.mainThread())
                        .doFinally(() -> setSyncButtonsBusy(false))
                        .subscribe(
                                result -> {
                                    if (getContext() != null) {
                                        Toast.makeText(
                                                getContext(),
                                                getString(
                                                        R.string.youtube_sync_subscriptions_success,
                                                        result.getTotalFetched(),
                                                        result.getAddedCount(),
                                                        result.getUpdatedCount()
                                                ),
                                                Toast.LENGTH_LONG
                                        ).show();
                                    }
                                },
                                error -> {
                                    if (getContext() != null) {
                                        Toast.makeText(
                                                getContext(),
                                                getString(R.string.youtube_sync_failed,
                                                        error.getMessage() != null ? error.getMessage() : ""),
                                                Toast.LENGTH_LONG
                                        ).show();
                                    }
                                }
                        )
        );
    }

    private void runHistorySync() {
        setSyncButtonsBusy(true);
        Toast.makeText(requireContext(), R.string.youtube_sync_in_progress, Toast.LENGTH_SHORT).show();
        disposables.add(
                YouTubeAccountSyncManager.syncWatchHistory(requireContext())
                        .observeOn(AndroidSchedulers.mainThread())
                        .doFinally(() -> setSyncButtonsBusy(false))
                        .subscribe(
                                result -> {
                                    if (getContext() != null) {
                                        Toast.makeText(
                                                getContext(),
                                                getString(
                                                        R.string.youtube_sync_history_success,
                                                        result.getTotalFetched(),
                                                        result.getAddedCount()
                                                ),
                                                Toast.LENGTH_LONG
                                        ).show();
                                    }
                                },
                                error -> {
                                    if (getContext() != null) {
                                        Toast.makeText(
                                                getContext(),
                                                getString(R.string.youtube_sync_failed,
                                                        error.getMessage() != null ? error.getMessage() : ""),
                                                Toast.LENGTH_LONG
                                        ).show();
                                    }
                                }
                        )
        );
    }

    @Override
    protected int getPreferenceResource() {
        return R.xml.account_settings_youtube;
    }

    @Override
    protected Class<?> getLoginActivityClass() {
        return YouTubeLoginWebViewActivity.class;
    }

    @Override
    protected void onLoginClicked() {
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.youtube_login_warning_title)
                .setMessage(R.string.youtube_login_warning_message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.ok, (dialog, which) -> startLoginActivity())
                .show();
    }

    @Override
    protected String getCookiesKey() {
        return getString(R.string.youtube_cookies_key);
    }

    @Override
    protected String getOverrideSwitchKey() {
        return getString(R.string.override_cookies_youtube_key);
    }

    @Override
    protected String getOverrideValueKey() {
        return getString(R.string.override_cookies_youtube_value_key);
    }

    @Override
    protected boolean shouldCheckOverrideKeys() {
        return false; // YouTube doesn't use override preferences
    }

    @Override
    protected void handleLoginResult(Intent data) {
        String cookies = data.getStringExtra("cookies");

        try {
            YoutubeParsingHelper.getAuthorizationHeader(cookies);
            defaultPreferences.edit()
                    .putString(getCookiesKey(), cookies)
                    .apply();
            LocalDomPoTokenProvider.INSTANCE.invalidate();
            onLoginSuccess();
            updateSyncPreferencesState();
            if (getContext() != null) {
                new AlertDialog.Builder(requireContext())
                        .setTitle(R.string.youtube_post_login_sync_title)
                        .setMessage(R.string.youtube_post_login_sync_message)
                        .setNegativeButton(R.string.later, null)
                        .setPositiveButton(R.string.youtube_sync_now_button,
                                (dialog, which) -> runFullSync())
                        .show();
            }
        } catch (Exception e) {
            Toast.makeText(requireContext(), R.string.try_again, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void performLogout() {
        defaultPreferences.edit()
                .putString(getCookiesKey(), "")
                .apply();
        LocalDomPoTokenProvider.INSTANCE.invalidate();
        LoginSessions.onLogout(requireContext(), LoginSessions.youtube(requireContext()));
        onLogoutSuccess();
        updateSyncPreferencesState();
    }

    @Override
    protected void refreshAccountDependentState() {
        super.refreshAccountDependentState();
        App.reconcileYoutubePlayerClient(requireContext());
        updateSyncPreferencesState();
    }

    @Override
    public void onResume() {
        super.onResume();
        updateSyncPreferencesState();
        getPreferenceScreen().getSharedPreferences().registerOnSharedPreferenceChangeListener(this);
    }

    @Override
    public void onPause() {
        super.onPause();
        getPreferenceScreen().getSharedPreferences().unregisterOnSharedPreferenceChangeListener(this);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        disposables.clear();
    }
}
