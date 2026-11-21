package org.telegram.messenger.car;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.content.Intent;
import android.net.Uri;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.car.app.CarContext;
import androidx.car.app.HostInfo;
import androidx.car.app.constraints.ConstraintManager;
import androidx.car.app.Screen;
import androidx.car.app.model.Action;
import androidx.car.app.model.CarIcon;
import androidx.car.app.model.CarText;
import androidx.car.app.model.ItemList;
import androidx.car.app.model.ListTemplate;
import androidx.car.app.model.MessageTemplate;
import androidx.car.app.model.Row;
import androidx.car.app.model.Tab;
import androidx.car.app.model.TabContents;
import androidx.car.app.model.TabTemplate;
import androidx.car.app.model.Template;
import androidx.car.app.messaging.model.CarMessage;
import androidx.car.app.messaging.model.ConversationCallback;
import androidx.car.app.messaging.model.ConversationItem;
import androidx.core.app.Person;
import androidx.core.graphics.drawable.IconCompat;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;

import org.telegram.messenger.AccountInstance;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.ContactsController;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.NotificationsController;
import org.telegram.messenger.R;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.TelegramMediaSession;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class HomeScreen extends Screen
        implements DefaultLifecycleObserver, NotificationCenter.NotificationCenterDelegate {

    private static final String TAB_NOTIFICATIONS = "tab_notifications";
    private static final String TAB_PINNED = "tab_pinned";
    private static final String TAB_MUSIC = "tab_music";

    private static final int MAX_CONVERSATIONS = 6;
    /** Upper bound for the pinned tab; the host's own list limit applies on top. */
    private static final int MAX_PINNED = 20;
    private static final int MAX_MESSAGES_PER_CONV = 5;
    private static final int MAX_MUSIC_DIALOGS = 50;

    private final long sessionStartMillis;
    private int currentAccount;

    /**
     * Voice notes whose download this screen requested, keyed by FileLoader attach name.
     * Only touched from the main thread: onGetTemplate is called by the host there, and
     * fileLoaded is delivered there too.
     */
    private final HashSet<String> pendingVoiceLoads = new HashSet<>();

    /** Voice-note URIs the host was granted read access to, so they can be revoked later. */
    private final HashSet<Uri> grantedUris = new HashSet<>();

    private String activeTabId = TAB_NOTIFICATIONS;
    private boolean musicLoadKicked;
    private boolean pinnedLoadKicked;

    public HomeScreen(@NonNull CarContext carContext) {
        super(carContext);
        sessionStartMillis = System.currentTimeMillis();
        currentAccount = UserConfig.selectedAccount;
        getLifecycle().addObserver(this);
    }

    @Override
    public void onResume(@NonNull LifecycleOwner owner) {
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.pushMessagesUpdated);
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.notificationsCountUpdated);
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.activeAccountChanged);
        // fileLoaded is posted per account, not globally, so it has to be observed on the
        // current account's center and moved whenever that account changes.
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.fileLoaded);
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.dialogsNeedReload);
    }

    @Override
    public void onPause(@NonNull LifecycleOwner owner) {
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.pushMessagesUpdated);
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.notificationsCountUpdated);
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.activeAccountChanged);
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.fileLoaded);
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.dialogsNeedReload);
    }

    @Override
    public void onDestroy(@NonNull LifecycleOwner owner) {
        revokeHostGrants();
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.activeAccountChanged) {
            // Unsubscribe from the outgoing account before currentAccount moves, or the
            // observer would be left registered on it forever.
            NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.fileLoaded);
            NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.dialogsNeedReload);
            // Drop grants before switching: they point at the outgoing account's files, and
            // the incoming account must not inherit read access to them.
            revokeHostGrants();
            currentAccount = UserConfig.selectedAccount;
            NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.fileLoaded);
            NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.dialogsNeedReload);
            pendingVoiceLoads.clear();
            musicLoadKicked = false;
            pinnedLoadKicked = false;
            invalidate();
        } else if (id == NotificationCenter.fileLoaded) {
            if (args.length > 0 && args[0] instanceof String
                    && pendingVoiceLoads.remove(args[0])
                    && TAB_NOTIFICATIONS.equals(activeTabId)) {
                // The note is on disk now; rebuilding attaches it as playable audio.
                invalidate();
            }
        } else if ((id == NotificationCenter.pushMessagesUpdated || id == NotificationCenter.notificationsCountUpdated)
                && (TAB_NOTIFICATIONS.equals(activeTabId) || TAB_PINNED.equals(activeTabId))) {
            invalidate();
        } else if (id == NotificationCenter.dialogsNeedReload && TAB_PINNED.equals(activeTabId)) {
            invalidate();
        }
    }

    @NonNull
    @Override
    public Template onGetTemplate() {
        TabTemplate.Builder builder = new TabTemplate.Builder(new TabTemplate.TabCallback() {
            @Override
            public void onTabSelected(@NonNull String tabContentId) {
                activeTabId = tabContentId;
                invalidate();
            }
        });
        builder.setHeaderAction(Action.APP_ICON);

        builder.addTab(new Tab.Builder()
                .setContentId(TAB_NOTIFICATIONS)
                .setIcon(iconResource(R.drawable.msg_notifications))
                .setTitle(LocaleController.getString(R.string.Notifications))
                .build());
        builder.addTab(new Tab.Builder()
                .setContentId(TAB_PINNED)
                .setIcon(iconResource(R.drawable.msg_pin))
                .setTitle(LocaleController.getString(R.string.CarPinnedChats))
                .build());
        builder.addTab(new Tab.Builder()
                .setContentId(TAB_MUSIC)
                .setIcon(iconResource(R.drawable.filled_widget_music))
                .setTitle(LocaleController.getString(R.string.Music))
                .build());

        builder.setActiveTabContentId(activeTabId);
        builder.setTabContents(new TabContents.Builder(buildTabContent(activeTabId)).build());
        return builder.build();
    }

    private Template buildTabContent(String tabId) {
        switch (tabId) {
            case TAB_MUSIC:
                return buildMusicTemplate();
            case TAB_PINNED:
                return buildPinnedTemplate();
            case TAB_NOTIFICATIONS:
            default:
                return buildNotificationsTemplate();
        }
    }

    // ===== Notifications tab =====

    private Template buildNotificationsTemplate() {
        Map<Long, ArrayList<MessageObject>> grouped = collectUnreadDuringDrive();
        if (grouped.isEmpty()) {
            return new MessageTemplate.Builder(LocaleController.getString(R.string.NoNewCarMessages))
                    .build();
        }
        ItemList.Builder list = new ItemList.Builder();
        int count = 0;
        for (Map.Entry<Long, ArrayList<MessageObject>> entry : grouped.entrySet()) {
            if (count++ >= MAX_CONVERSATIONS) break;
            ConversationItem item = buildConversationItem(entry.getKey(), entry.getValue(), false);
            if (item != null) list.addItem(item);
        }
        return new ListTemplate.Builder()
                .setSingleList(list.build())
                .build();
    }

    private Map<Long, ArrayList<MessageObject>> collectUnreadDuringDrive() {
        LinkedHashMap<Long, ArrayList<MessageObject>> grouped = new LinkedHashMap<>();
        NotificationsController nc = NotificationsController.getInstance(currentAccount);
        ArrayList<MessageObject> snapshot = nc.getPushMessagesSnapshot();
        long sessionStartSec = sessionStartMillis / 1000;
        for (MessageObject mo : snapshot) {
            if (mo == null || mo.messageOwner == null) continue;
            if (mo.messageOwner.date < sessionStartSec) continue;
            long dialogId = mo.getDialogId();
            if (DialogObject.isEncryptedDialog(dialogId)) continue;
            if (UserObject.isReplyUser(dialogId)) continue;
            ArrayList<MessageObject> bucket = grouped.get(dialogId);
            if (bucket == null) {
                bucket = new ArrayList<>();
                grouped.put(dialogId, bucket);
            }
            bucket.add(mo);
        }
        return grouped;
    }

    ConversationItem buildConversationItem(long dialogId, ArrayList<MessageObject> messages, boolean read) {
        if (messages.isEmpty()) return null;
        Collections.reverse(messages);
        AccountInstance ai = AccountInstance.getInstance(currentAccount);
        MessagesController mc = ai.getMessagesController();

        TLRPC.User user = null;
        TLRPC.Chat chat = null;
        String title;
        boolean isGroup = false;
        if (DialogObject.isUserDialog(dialogId)) {
            user = mc.getUser(dialogId);
            if (user == null) return null;
            title = ContactsController.formatName(user.first_name, user.last_name);
        } else {
            chat = mc.getChat(-dialogId);
            if (chat == null) return null;
            title = chat.title != null ? chat.title : "";
            isGroup = !ChatObject.isChannel(chat) || ChatObject.isMegagroup(chat);
        }

        Person.Builder convPersonBuilder = new Person.Builder()
                .setName(title)
                .setKey(String.valueOf(dialogId));
        // Without an icon the host draws the initial of the latest sender, which is
        // "You" whenever the last message is outgoing; hence the letter avatar fallback.
        IconCompat icon = avatarFor(user, chat);
        if (icon != null) convPersonBuilder.setIcon(icon);

        TLRPC.User self = UserConfig.getInstance(currentAccount).getCurrentUser();
        Person.Builder selfBuilder = new Person.Builder()
                .setName(LocaleController.getString(R.string.FromYou))
                .setKey("self_" + UserConfig.getInstance(currentAccount).clientUserId);
        if (self != null) {
            IconCompat selfIcon = loadUserAvatarIcon(self);
            if (selfIcon != null) selfBuilder.setIcon(selfIcon);
        }
        Person selfPerson = selfBuilder.build();

        List<CarMessage> carMessages = new ArrayList<>();
        int start = Math.max(0, messages.size() - MAX_MESSAGES_PER_CONV);
        for (int i = start; i < messages.size(); i++) {
            MessageObject mo = messages.get(i);
            CarMessage cm = buildCarMessage(mo, dialogId, isGroup, user, chat, read, selfPerson);
            if (cm != null) carMessages.add(cm);
        }
        if (carMessages.isEmpty()) return null;

        int latestMid = messages.get(messages.size() - 1).getId();

        ConversationItem.Builder cb = new ConversationItem.Builder()
                .setId(String.valueOf(dialogId))
                .setTitle(CarText.create(title))
                .setSelf(selfPerson)
                .setMessages(carMessages)
                .setGroupConversation(isGroup)
                .setConversationCallback(new TelegramConversationCallback(currentAccount, dialogId, latestMid));
        if (icon != null) {
            cb.setIcon(new CarIcon.Builder(icon).build());
        }
        return cb.build();
    }

    CarMessage buildCarMessage(MessageObject mo, long dialogId, boolean isGroup, TLRPC.User user, TLRPC.Chat chat, boolean read, Person selfPerson) {
        String[] senderName = new String[1];
        boolean[] preview = new boolean[1];
        String body;
        try {
            body = NotificationsController.getInstance(currentAccount)
                    .getShortStringForMessage(mo, senderName, preview);
        } catch (Throwable t) {
            body = mo.messageText != null ? mo.messageText.toString() : "";
        }
        if (body == null) return null;

        Person.Builder senderBuilder = new Person.Builder();
        if (senderName[0] != null) {
            senderBuilder.setName(senderName[0]).setKey("u" + mo.getSenderId());
        } else if (DialogObject.isUserDialog(dialogId) && user != null) {
            senderBuilder.setName(ContactsController.formatName(user.first_name, user.last_name))
                    .setKey("u" + user.id);
        } else if (chat != null) {
            senderBuilder.setName(chat.title != null ? chat.title : "").setKey("c" + chat.id);
        } else {
            senderBuilder.setName("").setKey("d" + dialogId);
        }

        CarMessage.Builder builder = new CarMessage.Builder()
                .setBody(CarText.create(body))
                .setReceivedTimeEpochMillis(((long) mo.messageOwner.date) * 1000L)
                // Outgoing messages must carry the same Person as setSelf(), so the host
                // presents them as "you" instead of attributing them to the chat partner.
                .setSender(mo.isOut() ? selfPerson : senderBuilder.build())
                .setRead(read);

        attachVoiceNote(builder, mo, preview[0]);

        return builder.build();
    }

    /**
     * Offers a received voice note to the car as playable audio. Without this the host only
     * gets the text body, which for a voice note is just a placeholder label.
     */
    private void attachVoiceNote(CarMessage.Builder builder, MessageObject mo, boolean previewAllowed) {
        if (!mo.isVoice()) {
            return;
        }
        boolean locked = AndroidUtilities.needShowPasscode() || SharedConfig.isWaitingForPasscodeEnter;
        if (!CarVoiceAttachment.isDisclosureAllowed(previewAllowed, locked)) {
            return;
        }
        File file = FileLoader.getInstance(currentAccount).getPathToMessage(mo.messageOwner);
        Uri uri = CarVoiceAttachment.resolveUri(
                ApplicationLoader.applicationContext,
                ApplicationLoader.getApplicationId() + ".provider",
                file,
                previewAllowed,
                locked);
        if (uri == null) {
            // Not on disk yet. Auto-download is off by default for voice notes, and in the
            // car this is the common case: the message usually arrives mid-drive.
            requestVoiceDownload(mo);
            return;
        }
        if (!grantReadToHost(uri)) {
            return;
        }
        builder.setMultimediaMimeType(CarVoiceAttachment.MIME_TYPE).setMultimediaUri(uri);
    }

    /**
     * Fetches a voice note that is not cached yet. The template is rebuilt once the download
     * lands, via the fileLoaded observer, so the message gains its player without the user
     * touching anything.
     */
    private void requestVoiceDownload(MessageObject mo) {
        TLRPC.Document document = mo.getDocument();
        if (document == null) {
            return;
        }
        String fileName = FileLoader.getAttachFileName(document);
        // add() also deduplicates: onGetTemplate runs on every invalidate, so without this
        // the same note would be queued again on each rebuild.
        if (TextUtils.isEmpty(fileName) || !pendingVoiceLoads.add(fileName)) {
            return;
        }
        FileLoader.getInstance(currentAccount).loadFile(document, mo, FileLoader.PRIORITY_HIGH, 0);
    }

    /**
     * The car host reads the URI from its own process, so a FileProvider grant is required.
     * CarMessage crosses Binder rather than travelling in an Intent, so the usual
     * FLAG_GRANT_READ_URI_PERMISSION on an Intent does not apply and the grant has to be
     * issued explicitly against the host package.
     *
     * @return whether the attachment may be offered; false means fall back to text only,
     *         since an unreadable URI would surface as a broken control in the car.
     */
    private boolean grantReadToHost(Uri uri) {
        try {
            HostInfo host = getCarContext().getHostInfo();
            if (host == null || TextUtils.isEmpty(host.getPackageName())) {
                return false;
            }
            getCarContext().grantUriPermission(
                    host.getPackageName(), uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            grantedUris.add(uri);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Drops the grants handed to the host, so read access does not outlive the drive.
     *
     * <p>Deliberately not tied to onPause or to a timer. The host reads the file when the
     * user presses play, which can be long after the message appeared, so revoking on a
     * delay -- as the notification code does for images that SystemUI reads immediately --
     * would risk cutting playback. Bounding the grants to the screen's lifetime and to the
     * active account keeps them from accumulating without that risk.
     */
    private void revokeHostGrants() {
        if (grantedUris.isEmpty()) {
            return;
        }
        for (Uri uri : grantedUris) {
            try {
                ApplicationLoader.applicationContext.revokeUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Throwable ignore) {
                // Already gone, or never granted; nothing to undo.
            }
        }
        grantedUris.clear();
    }

    // ===== Pinned tab =====

    /**
     * The chats and bots pinned in the main chat list, so frequent contacts and bot
     * integrations (Home Assistant and the like) can be reached by voice reply without
     * waiting for them to write first. Only the latest message is shown, and it counts as
     * read unless the chat has unread messages, so the host does not read it out again.
     */
    private Template buildPinnedTemplate() {
        MessagesController mc = MessagesController.getInstance(currentAccount);
        // getDialogs(0) is only filled once the chat list UI has sorted it, which never
        // happens when the car starts the process. allDialogs is populated by the load itself.
        ArrayList<TLRPC.Dialog> all = mc.getAllDialogs();
        if (!pinnedLoadKicked && mc.getDialogs(0).isEmpty()) {
            // The chat list was never opened in this process, so allDialogs may only hold the
            // few chats push updates touched. Load it from the local cache once;
            // dialogsNeedReload rebuilds the tab when it lands.
            pinnedLoadKicked = true;
            mc.loadDialogs(0, 0, 50, true);
            if (all.isEmpty()) {
                return new ListTemplate.Builder().setLoading(true).build();
            }
        }
        ArrayList<TLRPC.Dialog> pinned = new ArrayList<>();
        for (TLRPC.Dialog dialog : all) {
            if (dialog == null || !dialog.pinned || dialog.folder_id != 0) continue;
            if (dialog instanceof TLRPC.TL_dialogFolder || DialogObject.isFolderDialogId(dialog.id)) continue;
            if (DialogObject.isEncryptedDialog(dialog.id)) continue;
            pinned.add(dialog);
        }
        // Same order as the chat list: higher pinnedNum is further up.
        Collections.sort(pinned, (a, b) -> Integer.compare(b.pinnedNum, a.pinnedNum));

        int limit = Math.min(MAX_PINNED, hostListLimit());
        ItemList.Builder list = new ItemList.Builder();
        int added = 0;
        for (int i = 0; i < pinned.size() && added < limit; i++) {
            TLRPC.Dialog dialog = pinned.get(i);
            Row row = buildPinnedRow(mc, dialog);
            if (row != null) {
                list.addItem(row);
                added++;
            }
        }
        if (added == 0) {
            return new MessageTemplate.Builder(LocaleController.getString(R.string.CarNoPinnedChats))
                    .build();
        }
        return new ListTemplate.Builder()
                .setSingleList(list.build())
                .build();
    }

    /** One chat in the pinned list: avatar, name, unread count or time, latest message. */
    private Row buildPinnedRow(MessagesController mc, TLRPC.Dialog dialog) {
        long dialogId = dialog.id;
        TLRPC.User user = null;
        TLRPC.Chat chat = null;
        String title;
        if (DialogObject.isUserDialog(dialogId)) {
            user = mc.getUser(dialogId);
            if (user == null) return null;
            title = UserObject.isUserSelf(user)
                    ? LocaleController.getString(R.string.SavedMessages)
                    : ContactsController.formatName(user.first_name, user.last_name);
        } else {
            chat = mc.getChat(-dialogId);
            if (chat == null) return null;
            title = chat.title != null ? chat.title : "";
        }
        if (TextUtils.isEmpty(title)) title = " ";

        ArrayList<MessageObject> top = mc.dialogMessage.get(dialogId);
        MessageObject last = top == null || top.isEmpty() ? null : top.get(0);

        StringBuilder meta = new StringBuilder();
        if (dialog.unread_count > 0) {
            meta.append(LocaleController.formatPluralString("NewMessages", dialog.unread_count));
        } else if (last != null) {
            meta.append(LocaleController.stringForMessageListDate(last.messageOwner.date));
        }
        Row.Builder row = new Row.Builder()
                .setTitle(title)
                .setBrowsable(true);
        if (last != null) {
            String preview = messagePreview(last, true);
            if (meta.length() > 0) meta.append(" · ");
            meta.append(preview);
        }
        if (meta.length() > 0) row.addText(shorten(meta.toString(), PREVIEW_MAX_CHARS));
        IconCompat icon = avatarFor(user, chat);
        if (icon != null) {
            row.setImage(new CarIcon.Builder(icon).build(), Row.IMAGE_TYPE_LARGE);
        }
        final String screenTitle = title;
        row.setOnClickListener(() -> getScreenManager().push(
                new CarChatScreen(getCarContext(), this, currentAccount, dialogId, screenTitle)));
        return row.build();
    }

    /**
     * Short text for a message as the chat list shows it. With {@code withSender}, outgoing
     * messages get "You:" and group messages the sender's name in front.
     */
    String messagePreview(MessageObject mo, boolean withSender) {
        String[] senderName = new String[1];
        boolean[] preview = new boolean[1];
        String body;
        try {
            body = NotificationsController.getInstance(currentAccount)
                    .getShortStringForMessage(mo, senderName, preview);
        } catch (Throwable t) {
            body = mo.messageText != null ? mo.messageText.toString() : "";
        }
        if (body == null) body = "";
        if (!withSender) return body;
        if (mo.isOut()) {
            return LocaleController.getString(R.string.FromYou) + ": " + body;
        }
        if (senderName[0] != null && !DialogObject.isUserDialog(mo.getDialogId())) {
            return senderName[0] + ": " + body;
        }
        return body;
    }

    /** List previews stay on about one line; "Read aloud" still gets the full text. */
    static final int PREVIEW_MAX_CHARS = 60;

    /**
     * Collapses line breaks and runs of spaces, then cuts at a word boundary. Bot messages
     * (Paperclip, Home Assistant) are often long and multi-line, which otherwise makes a
     * single list entry fill the car screen.
     */
    static String shorten(String text, int maxChars) {
        if (text == null) return "";
        String flat = text.replaceAll("\\s+", " ").trim();
        if (flat.length() <= maxChars) return flat;
        int cut = flat.lastIndexOf(' ', maxChars);
        if (cut < maxChars / 2) cut = maxChars;
        return flat.substring(0, cut).trim() + "…";
    }

    int getCurrentAccount() {
        return currentAccount;
    }

    int hostListLimit() {
        try {
            return getCarContext().getCarService(ConstraintManager.class)
                    .getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_LIST);
        } catch (Throwable t) {
            return MAX_CONVERSATIONS;
        }
    }

    // ===== Music tab =====

    private Template buildMusicTemplate() {
        TelegramMediaSession session = TelegramMediaSession.getInstance(getCarContext().getApplicationContext());
        if (!session.isChatsLoaded()) {
            if (!musicLoadKicked) {
                musicLoadKicked = true;
                session.ensureLoaded(this::invalidate);
            }
            return new ListTemplate.Builder().setLoading(true).build();
        }
        ArrayList<Long> dialogs = session.getMusicDialogsSortedByVisibleOrder();
        if (dialogs == null || dialogs.isEmpty()) {
            return new MessageTemplate.Builder(LocaleController.getString(R.string.NoCarMusic))
                    .build();
        }
        ItemList.Builder list = new ItemList.Builder();
        int added = 0;
        for (int i = 0; i < dialogs.size() && added < MAX_MUSIC_DIALOGS; i++) {
            long dialogId = dialogs.get(i);
            ArrayList<MessageObject> messages = session.getMusicMessages(dialogId);
            if (messages == null || messages.isEmpty()) continue;
            String title;
            TLRPC.FileLocation avatarLoc = null;
            if (DialogObject.isUserDialog(dialogId)) {
                TLRPC.User u = session.getMusicUser(dialogId);
                if (u == null) continue;
                if (UserObject.isUserSelf(u)) {
                    title = LocaleController.getString(R.string.SavedMessages);
                } else {
                    title = ContactsController.formatName(u.first_name, u.last_name);
                    if (u.photo != null && !(u.photo.photo_small instanceof TLRPC.TL_fileLocationUnavailable)) {
                        avatarLoc = u.photo.photo_small;
                    }
                }
            } else {
                TLRPC.Chat c = session.getMusicChat(-dialogId);
                if (c == null) continue;
                title = c.title != null ? c.title : "";
                if (c.photo != null && !(c.photo.photo_small instanceof TLRPC.TL_fileLocationUnavailable)) {
                    avatarLoc = c.photo.photo_small;
                }
            }
            if (TextUtils.isEmpty(title)) continue;

            Row.Builder row = new Row.Builder()
                    .setTitle(title)
                    .addText(LocaleController.formatPluralString("MusicFiles", messages.size()))
                    .setBrowsable(true)
                    .setOnClickListener(() -> getScreenManager().push(new MusicSongsScreen(getCarContext(), dialogId, title)));

            IconCompat icon = avatarLoc != null
                    ? iconFromAvatar(avatarLoc)
                    : null;
            if (icon != null) {
                row.setImage(new CarIcon.Builder(icon).build(), Row.IMAGE_TYPE_LARGE);
            }
            list.addItem(row.build());
            added++;
        }
        return new ListTemplate.Builder()
                .setSingleList(list.build())
                .build();
    }

    // ===== Icon helpers =====

    private CarIcon iconResource(int resId) {
        return new CarIcon.Builder(IconCompat.createWithResource(getCarContext(), resId)).build();
    }

    private IconCompat loadAvatarIcon(TLRPC.User user, TLRPC.Chat chat) {
        TLRPC.FileLocation fileLocation = null;
        if (user != null && user.photo != null) {
            fileLocation = user.photo.photo_small;
        } else if (chat != null && chat.photo != null) {
            fileLocation = chat.photo.photo_small;
        }
        return iconFromAvatar(fileLocation);
    }

    /** Telegram's avatar palette (red, orange, violet, green, cyan, blue, pink). */
    private static final int[] AVATAR_COLORS = {
            0xFFE56555, 0xFFF28C48, 0xFF8E85EE, 0xFF76C84D, 0xFF5FBED5, 0xFF549CDD, 0xFFF2749A
    };

    /**
     * The letter avatar the chat list shows for chats without a photo. Drawn here rather
     * than with AvatarDrawable, whose paints only exist once the chat list UI has been
     * created -- which never happens when the car starts the process.
     */
    IconCompat placeholderAvatar(TLRPC.User user, TLRPC.Chat chat) {
        try {
            long id;
            String name;
            if (user != null) {
                id = user.id;
                name = ContactsController.formatName(user.first_name, user.last_name);
            } else if (chat != null) {
                id = chat.id;
                name = chat.title;
            } else {
                return null;
            }
            String trimmed = name == null ? "" : name.trim();
            String initial = trimmed.isEmpty() ? "?" : trimmed.substring(0, trimmed.offsetByCodePoints(0, 1)).toUpperCase();
            int size = AndroidUtilities.dp(64);
            Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            Paint circle = new Paint(Paint.ANTI_ALIAS_FLAG);
            circle.setColor(AVATAR_COLORS[(int) Math.abs(id % AVATAR_COLORS.length)]);
            canvas.drawCircle(size / 2f, size / 2f, size / 2f, circle);
            Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
            text.setColor(Color.WHITE);
            text.setTextSize(size * 0.45f);
            text.setTextAlign(Paint.Align.CENTER);
            text.setFakeBoldText(true);
            float baseline = size / 2f - (text.descent() + text.ascent()) / 2f;
            canvas.drawText(initial, size / 2f, baseline, text);
            return IconCompat.createWithBitmap(bitmap);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Photo if cached, otherwise the letter avatar. */
    IconCompat avatarFor(TLRPC.User user, TLRPC.Chat chat) {
        IconCompat icon = loadAvatarIcon(user, chat);
        return icon != null ? icon : placeholderAvatar(user, chat);
    }

    private IconCompat loadUserAvatarIcon(TLRPC.User user) {
        if (user == null || user.photo == null) return null;
        return iconFromAvatar(user.photo.photo_small);
    }

    private IconCompat iconFromAvatar(TLRPC.FileLocation loc) {
        if (loc == null || loc instanceof TLRPC.TL_fileLocationUnavailable) return null;
        try {
            File path = FileLoader.getInstance(currentAccount).getPathToAttach(loc, true);
            if (!path.exists()) return null;
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = 2;
            Bitmap bitmap = BitmapFactory.decodeFile(path.getAbsolutePath(), opts);
            if (bitmap == null) return null;
            Bitmap rounded = makeRound(bitmap);
            return IconCompat.createWithBitmap(rounded != null ? rounded : bitmap);
        } catch (Throwable t) {
            return null;
        }
    }

    private Bitmap makeRound(Bitmap source) {
        try {
            Bitmap dst = Bitmap.createBitmap(source.getWidth(), source.getHeight(), Bitmap.Config.ARGB_8888);
            dst.eraseColor(Color.TRANSPARENT);
            Canvas canvas = new Canvas(dst);
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setShader(new BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
            RectF r = new RectF(0, 0, source.getWidth(), source.getHeight());
            canvas.drawRoundRect(r, source.getWidth(), source.getHeight(), paint);
            return dst;
        } catch (Throwable t) {
            return null;
        }
    }

    private static final class TelegramConversationCallback implements ConversationCallback {
        private final int currentAccount;
        private final long dialogId;
        private final int maxId;

        TelegramConversationCallback(int currentAccount, long dialogId, int maxId) {
            this.currentAccount = currentAccount;
            this.dialogId = dialogId;
            this.maxId = maxId;
        }

        @Override
        public void onMarkAsRead() {
            AndroidUtilities.runOnUIThread(() -> {
                MessagesController.getInstance(currentAccount).markDialogAsRead(
                        dialogId, maxId, maxId, 0, false, 0, 0, true, 0);
                MessagesController.getInstance(currentAccount).markReactionsAsRead(dialogId, 0);
            });
        }

        @Override
        public void onTextReply(@NonNull String text) {
            AndroidUtilities.runOnUIThread(() -> {
                AccountInstance ai = AccountInstance.getInstance(currentAccount);
                ai.getSendMessagesHelper().sendMessage(SendMessagesHelper.SendMessageParams.of(
                        text, dialogId, null, null, null, true, null, null, null, true, 0, 0, null, false));
                MessagesController.getInstance(currentAccount).markDialogAsRead(
                        dialogId, maxId, maxId, 0, false, 0, 0, true, 0);
            });
        }
    }
}
