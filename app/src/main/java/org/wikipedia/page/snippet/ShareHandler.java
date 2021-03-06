package org.wikipedia.page.snippet;

import android.content.Context;
import android.content.DialogInterface;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.support.annotation.ColorInt;
import android.support.annotation.ColorRes;
import android.support.annotation.IntegerRes;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.content.ContextCompat;
import android.view.ActionMode;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ImageView;

import com.appenguin.onboarding.ToolTip;

import org.apache.commons.lang3.StringUtils;
import org.json.JSONException;
import org.json.JSONObject;
import org.wikipedia.R;
import org.wikipedia.WikipediaApp;
import org.wikipedia.activity.ActivityUtil;
import org.wikipedia.analytics.ShareAFactFunnel;
import org.wikipedia.bridge.CommunicationBridge;
import org.wikipedia.dataclient.mwapi.MwQueryResponse;
import org.wikipedia.gallery.ImageLicense;
import org.wikipedia.gallery.ImageLicenseFetchClient;
import org.wikipedia.page.Namespace;
import org.wikipedia.page.NoDimBottomSheetDialog;
import org.wikipedia.page.Page;
import org.wikipedia.page.PageFragment;
import org.wikipedia.page.PageProperties;
import org.wikipedia.page.PageTitle;
import org.wikipedia.settings.Prefs;
import org.wikipedia.tooltip.ToolTipUtil;
import org.wikipedia.util.FeedbackUtil;
import org.wikipedia.util.ShareUtil;
import org.wikipedia.util.StringUtil;
import org.wikipedia.util.UriUtil;
import org.wikipedia.util.log.L;
import org.wikipedia.wiktionary.WiktionaryDialog;

import java.util.Arrays;

import retrofit2.Call;

import static org.wikipedia.analytics.ShareAFactFunnel.ShareMode;

/**
 * Let user choose between sharing as text or as image.
 */
public class ShareHandler {
    private static final String PAYLOAD_PURPOSE_KEY = "purpose";
    private static final String PAYLOAD_PURPOSE_SHARE = "share";
    private static final String PAYLOAD_PURPOSE_DEFINE = "define";
    private static final String PAYLOAD_PURPOSE_EDIT_HERE = "edit_here";
    private static final String PAYLOAD_TEXT_KEY = "text";

    @ColorRes private static final int SHARE_TOOL_TIP_COLOR = R.color.foundation_blue;

    @NonNull private final PageFragment fragment;
    @NonNull private final CommunicationBridge bridge;
    @Nullable private ActionMode webViewActionMode;
    @Nullable private ShareAFactFunnel funnel;

    private void createFunnel() {
        WikipediaApp app = WikipediaApp.getInstance();
        final Page page = fragment.getPage();
        final PageProperties pageProperties = page.getPageProperties();
        funnel = new ShareAFactFunnel(app, page.getTitle(), pageProperties.getPageId(),
                pageProperties.getRevisionId());
    }

    public ShareHandler(@NonNull PageFragment fragment, @NonNull CommunicationBridge bridge) {
        this.fragment = fragment;
        this.bridge = bridge;

        bridge.addListener("onGetTextSelection", new CommunicationBridge.JSEventListener() {
            @Override
            public void onMessage(String messageType, JSONObject messagePayload) {
                String purpose = messagePayload.optString(PAYLOAD_PURPOSE_KEY, "");
                String text = messagePayload.optString(PAYLOAD_TEXT_KEY, "");
                switch (purpose) {
                    case PAYLOAD_PURPOSE_SHARE:
                        onSharePayload(text);
                        break;
                    case PAYLOAD_PURPOSE_DEFINE:
                        onDefinePayload(text);
                        break;
                    case PAYLOAD_PURPOSE_EDIT_HERE:
                        onEditHerePayload(messagePayload.optInt("sectionID", 0), text);
                        break;
                    default:
                        L.d("Unknown purpose=" + purpose);
                }
            }
        });
    }

    private void onHighlightText() {
        if (funnel == null) {
            createFunnel();
        }
        funnel.logHighlight();
    }

    public void showWiktionaryDefinition(String text) {
        PageTitle title = fragment.getTitle();
        fragment.showBottomSheet(WiktionaryDialog.newInstance(title, text));
    }

    private void onSharePayload(@NonNull String text) {
        if (funnel == null) {
            createFunnel();
        }
        shareSnippet(text);
        funnel.logShareTap(text);
    }

    private void onDefinePayload(String text) {
        showWiktionaryDefinition(text.toLowerCase());
    }

    private void onEditHerePayload(int sectionID, String text) {
        fragment.getEditHandler().startEditingSection(sectionID, text);
    }

    private void showCopySnackbar() {
        FeedbackUtil.showMessage(fragment.getActivity(), R.string.text_copied);
    }

    private void shareSnippet(@NonNull CharSequence input) {
        final String selectedText = StringUtil.sanitizeText(input.toString());
        final PageTitle title = fragment.getTitle();
        final String leadImageNameText = fragment.getPage().getPageProperties().getLeadImageName() != null
                ? fragment.getPage().getPageProperties().getLeadImageName() : "";

        new ImageLicenseFetchClient().request(title.getWikiSite(),
                new PageTitle(Namespace.FILE.toLegacyString(), leadImageNameText, title.getWikiSite()),
                new ImageLicenseFetchClient.Callback() {
                    @Override public void success(@NonNull Call<MwQueryResponse> call,
                                                  @NonNull ImageLicense result) {
                        final Bitmap snippetBitmap = SnippetImage.getSnippetImage(fragment.getContext(),
                                fragment.getLeadImageBitmap(),
                                title.getDisplayText(),
                                fragment.getPage().isMainPage() ? "" : StringUtils.capitalize(title.getDescription()),
                                selectedText,
                                result);
                        fragment.showBottomSheet(new PreviewDialog(fragment.getContext(),
                                snippetBitmap, title, selectedText, funnel));
                    }

                    @Override public void failure(@NonNull Call<MwQueryResponse> call,
                                                  @NonNull Throwable caught) {
                        // If we failed to get license info for the lead image, just share the text
                        PreviewDialog.shareAsText(fragment.getContext(), title, selectedText, funnel);
                        L.e("Error fetching image license info for " + title.getDisplayText(), caught);
                    }
                });
    }

    /**
     * @param mode ActionMode under which this context is starting.
     */
    public void onTextSelected(ActionMode mode) {
        webViewActionMode = mode;
        Menu menu = mode.getMenu();
        MenuItem shareItem = menu.findItem(R.id.menu_text_select_share);
        handleSelection(menu, shareItem);
    }

    private void handleSelection(Menu menu, MenuItem shareItem) {
        if (WikipediaApp.getInstance().getOnboardingStateMachine().isShareTutorialEnabled()) {
            postShowShareToolTip(shareItem);
            WikipediaApp.getInstance().getOnboardingStateMachine().setShareTutorial();
        }

        // Provide our own listeners for the copy, define, and share buttons.
        shareItem.setOnMenuItemClickListener(new RequestTextSelectOnMenuItemClickListener(PAYLOAD_PURPOSE_SHARE));
        MenuItem copyItem = menu.findItem(R.id.menu_text_select_copy);
        copyItem.setOnMenuItemClickListener(new MenuItem.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem menuItem) {
                fragment.getWebView().copyToClipboard();
                showCopySnackbar();
                leaveActionMode();
                return true;
            }
        });
        MenuItem defineItem = menu.findItem(R.id.menu_text_select_define);
        if (shouldEnableWiktionaryDialog()) {
            defineItem.setVisible(true);
            defineItem.setOnMenuItemClickListener(new RequestTextSelectOnMenuItemClickListener(PAYLOAD_PURPOSE_DEFINE));
        }
        MenuItem editItem = menu.findItem(R.id.menu_text_edit_here);
        editItem.setOnMenuItemClickListener(new RequestTextSelectOnMenuItemClickListener(PAYLOAD_PURPOSE_EDIT_HERE));
        if (!fragment.getPage().isArticle()) {
            editItem.setVisible(false);
        }

        onHighlightText();
    }

    private boolean shouldEnableWiktionaryDialog() {
        return Prefs.useRestBase() && isWiktionaryDialogEnabledForArticleLanguage();
    }

    private boolean isWiktionaryDialogEnabledForArticleLanguage() {
        return Arrays.asList(WiktionaryDialog.getEnabledLanguages())
                .contains(fragment.getTitle().getWikiSite().languageCode());
    }

    private void postShowShareToolTip(final MenuItem shareItem) {
        // There doesn't seem to be good lifecycle event accessible at the time this called to
        // ensure the tool tip is shown after CAB animation.

        final View shareItemView = ActivityUtil.getMenuItemView(fragment.getActivity(), shareItem);
        if (shareItemView != null) {
            int delay = getInteger(android.R.integer.config_longAnimTime);
            shareItemView.postDelayed(new Runnable() {
                @Override
                public void run() {
                    showShareToolTip(shareItemView);
                }
            }, delay);
        }
    }

    private void showShareToolTip(View shareItemView) {
        ToolTipUtil.showToolTip(fragment.getActivity(), shareItemView, R.layout.inflate_tool_tip_share,
                getColor(SHARE_TOOL_TIP_COLOR), ToolTip.Position.CENTER);
    }

    @ColorInt
    private int getColor(@ColorRes int id) {
        return ContextCompat.getColor(fragment.getContext(), id);
    }

    private int getInteger(@IntegerRes int id) {
        return getResources().getInteger(id);
    }

    private Resources getResources() {
        return fragment.getContext().getResources();
    }

    private void leaveActionMode() {
        if (hasWebViewActionMode()) {
            finishWebViewActionMode();
            nullifyWebViewActionMode();
        }
    }

    private boolean hasWebViewActionMode() {
        return webViewActionMode != null;
    }

    private void nullifyWebViewActionMode() {
        webViewActionMode = null;
    }

    private void finishWebViewActionMode() {
        webViewActionMode.finish();
    }

    private class RequestTextSelectOnMenuItemClickListener implements MenuItem.OnMenuItemClickListener {
        @NonNull private final String purpose;

        RequestTextSelectOnMenuItemClickListener(@NonNull String purpose) {
            this.purpose = purpose;
        }

        @Override
        public boolean onMenuItemClick(MenuItem item) {
            requestTextSelection(purpose);
            leaveActionMode();
            return true;
        }
    }

    private void requestTextSelection(String purpose) {
        // send an event to the WebView that will make it return the
        // selected text (or first paragraph) back to us...
        try {
            JSONObject payload = new JSONObject();
            payload.put(PAYLOAD_PURPOSE_KEY, purpose);
            bridge.sendMessage("getTextSelection", payload);
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
    }
}

/**
 * A dialog to be displayed before sharing with two action buttons:
 * "Share as image", "Share as text".
 */
class PreviewDialog extends NoDimBottomSheetDialog {
    private boolean completed = false;

    PreviewDialog(final Context context, final Bitmap resultBitmap, final PageTitle title,
                  final String selectedText, final ShareAFactFunnel funnel) {
        super(context);
        View rootView = LayoutInflater.from(context).inflate(R.layout.dialog_share_preview, null);
        setContentView(rootView);
        ImageView previewImage = (ImageView) rootView.findViewById(R.id.preview_img);
        previewImage.setImageBitmap(resultBitmap);
        rootView.findViewById(R.id.close_button)
                .setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        dismiss();
                    }
                });
        rootView.findViewById(R.id.share_as_image_button)
                .setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        String introText = context.getString(R.string.snippet_share_intro,
                                title.getDisplayText(),
                                UriUtil.getUrlWithProvenance(context, title, R.string.prov_share_image));
                        ShareUtil.shareImage(context, resultBitmap, title.getDisplayText(),
                                title.getDisplayText(), introText);
                        funnel.logShareIntent(selectedText, ShareMode.image);
                        completed = true;
                    }
                });
        rootView.findViewById(R.id.share_as_text_button)
                .setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        shareAsText(context, title, selectedText, funnel);
                        completed = true;
                    }
                });
        setOnDismissListener(new OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface dialog) {
                resultBitmap.recycle();
                if (!completed) {
                    funnel.logAbandoned(title.getDisplayText());
                }
            }
        });
        startExpanded();
    }

    static void shareAsText(@NonNull Context context, @NonNull PageTitle title,
                            @NonNull String selectedText, @Nullable ShareAFactFunnel funnel) {
        String introText = context.getString(R.string.snippet_share_intro,
                title.getDisplayText(),
                UriUtil.getUrlWithProvenance(context, title, R.string.prov_share_text));
        ShareUtil.shareText(context, title.getDisplayText(),
                constructShareText(selectedText, introText));
        if (funnel != null) {
            funnel.logShareIntent(selectedText, ShareMode.text);
        }
    }

    private static String constructShareText(String selectedText, String introText) {
        return selectedText + "\n\n" + introText;
    }
}
