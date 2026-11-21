package org.telegram.messenger.car;

import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.car.app.CarContext;
import androidx.car.app.Screen;
import androidx.car.app.messaging.model.ConversationItem;
import androidx.car.app.model.Action;
import androidx.car.app.model.ItemList;
import androidx.car.app.model.ListTemplate;
import androidx.car.app.model.MessageTemplate;
import androidx.car.app.model.Row;
import androidx.car.app.model.Template;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;

import org.telegram.messenger.ContactsController;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;

/**
 * One pinned chat in the car: the reply/read-aloud conversation on top, then the latest
 * messages one per row, newest first.
 */
public class CarChatScreen extends Screen
        implements DefaultLifecycleObserver, NotificationCenter.NotificationCenterDelegate {

    private static final int LOAD_COUNT = 20;
    /** Rows below the conversation item; the host's list limit applies on top. */
    private static final int MAX_MESSAGE_ROWS = 15;
    /** About two lines per message row; the full text is read aloud on request. */
    private static final int MESSAGE_ROW_MAX_CHARS = 120;

    private final HomeScreen home;
    private final int currentAccount;
    private final long dialogId;
    private final String title;
    private final int classGuid = ConnectionsManager.generateClassGuid();

    private final HashMap<Integer, MessageObject> messages = new HashMap<>();
    private boolean loading = true;
    private boolean serverRequested;
    private int loadIndex;

    public CarChatScreen(@NonNull CarContext carContext, @NonNull HomeScreen home,
                         int currentAccount, long dialogId, @NonNull String title) {
        super(carContext);
        this.home = home;
        this.currentAccount = currentAccount;
        this.dialogId = dialogId;
        this.title = TextUtils.isEmpty(title) ? " " : title;
        // Show the latest message right away; the load fills in the rest.
        ArrayList<MessageObject> top = MessagesController.getInstance(currentAccount).dialogMessage.get(dialogId);
        if (top != null) {
            for (MessageObject mo : top) add(mo);
        }
        getLifecycle().addObserver(this);
    }

    @Override
    public void onCreate(@NonNull LifecycleOwner owner) {
        NotificationCenter nc = NotificationCenter.getInstance(currentAccount);
        nc.addObserver(this, NotificationCenter.messagesDidLoad);
        nc.addObserver(this, NotificationCenter.didReceiveNewMessages);
        load(true);
    }

    @Override
    public void onDestroy(@NonNull LifecycleOwner owner) {
        NotificationCenter nc = NotificationCenter.getInstance(currentAccount);
        nc.removeObserver(this, NotificationCenter.messagesDidLoad);
        nc.removeObserver(this, NotificationCenter.didReceiveNewMessages);
    }

    private void load(boolean fromCache) {
        loading = true;
        MessagesController.getInstance(currentAccount).loadMessages(
                dialogId, 0, false, LOAD_COUNT, 0, 0, fromCache, 0, classGuid,
                0, 0, 0, 0, 0, loadIndex++, false);
    }

    private void add(MessageObject mo) {
        if (mo == null || mo.messageOwner == null || mo.getDialogId() != dialogId) return;
        if (mo.getId() == 0 || mo.scheduled) return;
        messages.put(mo.getId(), mo);
    }

    @Override
    @SuppressWarnings("unchecked")
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.messagesDidLoad) {
            if (args.length <= 10 || !(args[10] instanceof Integer) || (Integer) args[10] != classGuid) {
                return;
            }
            ArrayList<MessageObject> loaded = (ArrayList<MessageObject>) args[2];
            boolean isCache = (Boolean) args[3];
            if (loaded != null) {
                for (MessageObject mo : loaded) add(mo);
            }
            if (isCache && !serverRequested && (loaded == null || loaded.size() < LOAD_COUNT)) {
                // The local cache is thin for chats that were never opened on this device.
                serverRequested = true;
                load(false);
            } else {
                loading = false;
            }
            invalidate();
        } else if (id == NotificationCenter.didReceiveNewMessages) {
            if (args.length < 2 || !(args[0] instanceof Long) || (Long) args[0] != dialogId) return;
            if (args.length > 2 && Boolean.TRUE.equals(args[2])) return; // scheduled messages
            ArrayList<MessageObject> arr = (ArrayList<MessageObject>) args[1];
            if (arr == null) return;
            for (MessageObject mo : arr) add(mo);
            invalidate();
        }
    }

    @NonNull
    @Override
    public Template onGetTemplate() {
        ArrayList<MessageObject> newestFirst = new ArrayList<>(messages.values());
        Collections.sort(newestFirst, (a, b) -> Integer.compare(b.getId(), a.getId()));

        if (newestFirst.isEmpty()) {
            if (loading) {
                return new ListTemplate.Builder()
                        .setTitle(title)
                        .setHeaderAction(Action.BACK)
                        .setLoading(true)
                        .build();
            }
            return new MessageTemplate.Builder(LocaleController.getString(R.string.NoMessages))
                    .setTitle(title)
                    .setHeaderAction(Action.BACK)
                    .build();
        }

        MessagesController mc = MessagesController.getInstance(currentAccount);
        TLRPC.Dialog dialog = mc.dialogs_dict.get(dialogId);
        boolean read = dialog == null || dialog.unread_count <= 0;

        ItemList.Builder list = new ItemList.Builder();
        int limit = home.hostListLimit();
        int added = 0;

        // Read aloud and voice reply live on the conversation item; it takes the newest
        // messages itself (buildConversationItem expects them newest first).
        ConversationItem conversation = home.buildConversationItem(dialogId, new ArrayList<>(newestFirst), read);
        if (conversation != null) {
            list.addItem(conversation);
            added++;
        }

        for (int i = 0; i < newestFirst.size() && i < MAX_MESSAGE_ROWS && added < limit; i++) {
            MessageObject mo = newestFirst.get(i);
            String body = home.messagePreview(mo, false);
            if (TextUtils.isEmpty(body)) continue;
            String header = senderLabel(mc, mo) + " · "
                    + LocaleController.stringForMessageListDate(mo.messageOwner.date);
            list.addItem(new Row.Builder()
                    .setTitle(header)
                    .addText(HomeScreen.shorten(body, MESSAGE_ROW_MAX_CHARS))
                    .build());
            added++;
        }

        return new ListTemplate.Builder()
                .setTitle(title)
                .setHeaderAction(Action.BACK)
                .setSingleList(list.build())
                .build();
    }

    private String senderLabel(MessagesController mc, MessageObject mo) {
        if (mo.isOut()) {
            return LocaleController.getString(R.string.FromYou);
        }
        long senderId = mo.getSenderId();
        if (DialogObject.isUserDialog(senderId)) {
            TLRPC.User u = mc.getUser(senderId);
            if (u != null) return ContactsController.formatName(u.first_name, u.last_name);
        } else {
            TLRPC.Chat c = mc.getChat(-senderId);
            if (c != null && c.title != null) return c.title;
        }
        return title;
    }
}
