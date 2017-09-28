package forpdateam.ru.forpda;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.support.annotation.DrawableRes;
import android.support.annotation.Nullable;
import android.support.v4.app.NotificationManagerCompat;
import android.support.v4.util.ArraySet;
import android.support.v7.app.NotificationCompat;
import android.util.Log;
import android.util.SparseArray;

import com.nostra13.universalimageloader.core.ImageLoader;

import java.util.ArrayList;
import java.util.List;
import java.util.Observer;
import java.util.Set;
import java.util.regex.Matcher;

import forpdateam.ru.forpda.api.Api;
import forpdateam.ru.forpda.api.Utils;
import forpdateam.ru.forpda.api.events.NotificationEvents;
import forpdateam.ru.forpda.api.events.models.NotificationEvent;
import forpdateam.ru.forpda.api.others.user.ForumUser;
import forpdateam.ru.forpda.client.Client;
import forpdateam.ru.forpda.client.ClientHelper;
import forpdateam.ru.forpda.data.models.TabNotification;
import forpdateam.ru.forpda.rxapi.ForumUsersCache;
import forpdateam.ru.forpda.settings.Preferences;
import forpdateam.ru.forpda.utils.BitmapUtils;
import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.functions.Consumer;
import io.reactivex.schedulers.Schedulers;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/**
 * Created by radiationx on 31.07.17.
 */

public class NotificationsService extends Service {
    private final static String LOG_TAG = NotificationsService.class.getSimpleName();
    private final static int NOTIFY_STACKED_QMS_ID = -123;
    private final static int NOTIFY_STACKED_FAV_ID = -234;
    public final static String CHECK_LAST_EVENTS = "CHECK_LAST_EVENTS";
    private NotificationManagerCompat mNotificationManager;
    private SparseArray<NotificationEvent> eventsHistory = new SparseArray<>();
    private WebSocket webSocket;
    private long lastHardCheckTime = 0;

    /*private boolean notificationsEnabled = Preferences.Notifications.Main.isEnabled();
    private boolean favoritesEnabled = Preferences.Notifications.Main.isEnabled();
    private boolean mentionsEnabled = Preferences.Notifications.Main.isEnabled();
    private boolean qmsEnabled = Preferences.Notifications.Main.isEnabled();*/

    private Observer loginObserver = (observable, o) -> {
        if (o == null) o = false;

    };
    private Observer networkObserver = (observable, o) -> {
        if (o == null) o = true;
        if ((boolean) o) {
            if (Preferences.Notifications.Main.isEnabled()) {
                start(true);
            }
        }
    };

    private Observer notificationSettingObserver = (observable, o) -> {
        if (o == null) return;
        String key = (String) o;
        switch (key) {
            case Preferences.Notifications.Main.ENABLED: {
                if (Preferences.Notifications.Main.isEnabled()) {
                    start(true);
                } else {
                    stop();
                }
                break;
            }
            case Preferences.Notifications.Favorites.ENABLED: {
                if (Preferences.Notifications.Favorites.isEnabled()) {
                    handleEvent(NotificationEvent.Source.THEME);
                }
                break;
            }
            case Preferences.Notifications.Qms.ENABLED: {
                if (Preferences.Notifications.Qms.isEnabled()) {
                    handleEvent(NotificationEvent.Source.QMS);
                }
                break;
            }
            /*case Preferences.Notifications.Mentions.ENABLED: {

                break;
            }*/
        }
    };

    private WebSocketListener webSocketListener = new WebSocketListener() {
        Matcher matcher = null;

        @Override
        public void onOpen(WebSocket webSocket, Response response) {
            Log.d(LOG_TAG, "WSListener onOpen: " + response.toString());
        }

        @Override
        public void onMessage(WebSocket webSocket, String text) {
            Log.d(LOG_TAG, "WSListener onMessage: " + text);
            if (matcher == null) {
                matcher = NotificationEvents.webSocketEventPattern.matcher(text);
            } else {
                matcher = matcher.reset(text);
            }
            NotificationEvent event = Api.UniversalEvents().parseWebSocketEvent(matcher);
            try {
                if (event != null) {
                    if (event.getType() != NotificationEvent.Type.HAT_EDITED) {
                        handleWebSocketEvent(event);
                    }
                }
            } catch (Exception ex) {
                ex.printStackTrace();
            }

        }

        @Override
        public void onMessage(WebSocket webSocket, ByteString bytes) {
            Log.d(LOG_TAG, "WSListener onMessage: " + bytes.hex());
        }

        @Override
        public void onClosing(WebSocket webSocket, int code, String reason) {
            Log.d(LOG_TAG, "WSListener onClosing: " + code + " " + reason);
            webSocket.close(1000, null);
        }

        @Override
        public void onClosed(WebSocket webSocket, int code, String reason) {
            Log.d(LOG_TAG, "WSListener onClosed: " + code + " " + reason);
        }

        @Override
        public void onFailure(WebSocket webSocket, Throwable t, Response response) {
            Log.d(LOG_TAG, "WSListener onFailure: " + t.getMessage() + " " + response);
            t.printStackTrace();
            stop();
            new Handler().postDelayed(()->{
                start(false);
            }, 1000);
        }
    };

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        Log.i(LOG_TAG, "onCreate");
        Client.getInstance().addNetworkObserver(networkObserver);
        App.get().addPreferenceChangeObserver(notificationSettingObserver);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(LOG_TAG, "onStartCommand args" + flags + " : " + startId + " : " + intent);
        Log.i(LOG_TAG, "onStartCommand websocket" + webSocket);
        if (mNotificationManager == null) {
            mNotificationManager = NotificationManagerCompat.from(this);
        }
        if (Preferences.Notifications.Main.isEnabled()) {
            boolean checkEvents = intent != null && intent.getAction() != null && intent.getAction().equals(CHECK_LAST_EVENTS);
            long time = System.currentTimeMillis();

            Log.d(LOG_TAG, "Handle check last events: " + time + " : " + lastHardCheckTime + " : " + (time - lastHardCheckTime));

            if (checkEvents && ((time - lastHardCheckTime) >= 1000 * 60 * 1)) {
                lastHardCheckTime = time;
                checkEvents = true;
            } else {
                checkEvents = false;
            }
            start(checkEvents);
        }
        return START_STICKY;
    }

    private void start(boolean checkEvents) {
        if (Client.getInstance().getNetworkState()) {
            if (webSocket == null) {
                webSocket = Client.getInstance().createWebSocketConnection(webSocketListener);
            }
            webSocket.send("[0,\"sv\"]");
            webSocket.send("[0, \"ea\", \"u" + ClientHelper.getUserId() + "\"]");
            if (checkEvents) {
                handleEvent(NotificationEvent.Source.THEME);
                handleEvent(NotificationEvent.Source.QMS);
            }
        }
    }

    private void stop() {
        if (webSocket != null){
            try{
                webSocket.close(1000, null);
            }catch (Exception ex){
                ex.printStackTrace();
            }
        }
        webSocket = null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.i(LOG_TAG, "onDestroy");
        App.get().removePreferenceChangeObserver(notificationSettingObserver);
        Client.getInstance().removeNetworkObserver(networkObserver);
        stop();
    }


    @Override
    public void onTaskRemoved(Intent rootIntent) {
        Log.i(LOG_TAG, "onTaskRemoved");
        if (webSocket != null) {
            webSocket.close(1000, null);
        }
        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.KITKAT) {
            Intent restartIntent = new Intent(this, getClass());

            AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
            PendingIntent pi = PendingIntent.getService(this, 1, restartIntent, PendingIntent.FLAG_ONE_SHOT);
            restartIntent.putExtra("RESTART", "RESTART_CHEBUREK");
            am.setExact(AlarmManager.RTC, System.currentTimeMillis() + 3000, pi);
        }
    }

    private void handleWebSocketEvent(NotificationEvent event) {
        if (event.isRead()) {
            TabNotification tabNotification = new TabNotification();
            tabNotification.setType(event.getType());
            tabNotification.setSource(event.getSource());
            tabNotification.setEvent(event);
            notifyTabs(tabNotification);
            NotificationEvent oldEvent = eventsHistory.get(event.notifyId(NotificationEvent.Type.NEW));
            boolean delete = false;

            if (event.fromTheme()) {
                //Убираем уведомления избранного
                if (oldEvent != null && event.getMessageId() >= oldEvent.getMessageId()) {
                    mNotificationManager.cancel(oldEvent.notifyId());
                    delete = true;
                }

                //Убираем уведомление упоминаний
                oldEvent = eventsHistory.get(event.notifyId(NotificationEvent.Type.MENTION));
                if (oldEvent != null) {
                    mNotificationManager.cancel(oldEvent.notifyId());
                    delete = true;
                }
            } else if (event.fromQms()) {

                //Убираем уведомление кумыса
                if (oldEvent != null) {
                    mNotificationManager.cancel(oldEvent.notifyId());
                    delete = true;
                }
            }

            if (delete) {
                eventsHistory.remove(event.notifyId(NotificationEvent.Type.NEW));
            }
            return;
        }
        handleEvent(event);
    }

    private void handleEvent(NotificationEvent.Source source) {
        handleEvent(null, source);
    }

    private void handleEvent(NotificationEvent event) {
        handleEvent(event, event.getSource());
    }

    private void handleEvent(@Nullable NotificationEvent event, NotificationEvent.Source source) {
        if (!Preferences.Notifications.Main.isEnabled()) {
            return;
        }

        if (NotificationEvent.fromSite(source)) {
            if (Preferences.Notifications.Mentions.isEnabled()) {
                sendNotification(event);
            }
            return;
        }
        if (NotificationEvent.fromQms(source)) {
            if (!Preferences.Notifications.Qms.isEnabled()) {
                return;
            }
        } else if (NotificationEvent.fromTheme(source)) {
            if (event != null && event.isMention()) {
                if (!Preferences.Notifications.Mentions.isEnabled()) {
                    return;
                }
            } else {
                if (!Preferences.Notifications.Favorites.isEnabled()) {
                    return;
                }
            }
        }

        loadEvents(loadedEvents -> {
            List<NotificationEvent> savedEvents = getSavedEvents(source);
            //savedEvents = new ArrayList<>();
            saveEvents(loadedEvents, source);
            List<NotificationEvent> newEvents = compareEvents(savedEvents, loadedEvents, event, source);
            List<NotificationEvent> stackedNewEvents = new ArrayList<>(newEvents);

            if (event != null) {
                //Удаляем из общего уведомления текущее уведомление
                for (NotificationEvent newEvent : newEvents) {
                    if (newEvent.getSourceId() == event.getSourceId()) {
                        stackedNewEvents.remove(newEvent);
                        newEvent.setType(event.getType());
                        newEvent.setMessageId(event.getMessageId());

                        TabNotification tabNotification = new TabNotification();
                        tabNotification.setType(newEvent.getType());
                        tabNotification.setSource(newEvent.getSource());
                        tabNotification.setEvent(newEvent);
                        tabNotification.getLoadedEvents().addAll(loadedEvents);
                        tabNotification.getNewEvents().addAll(newEvents);
                        notifyTabs(tabNotification);

                        sendNotification(newEvent);
                    } else if (event.isMention() && !Preferences.Notifications.Favorites.isEnabled()) {
                        stackedNewEvents.remove(newEvent);
                    }
                }
            }

            sendNotifications(stackedNewEvents);
        }, source);
    }

    private List<NotificationEvent> getSavedEvents(NotificationEvent.Source source) {
        String prefKey = "";
        if (NotificationEvent.fromQms(source)) {
            prefKey = Preferences.Notifications.Data.QMS_EVENTS;
        } else if (NotificationEvent.fromTheme(source)) {
            prefKey = Preferences.Notifications.Data.FAVORITES_EVENTS;
        }

        Set<String> savedEvents = App.get().getPreferences().getStringSet(prefKey, new ArraySet<>());
        StringBuilder responseBuilder = new StringBuilder();
        for (String saved : savedEvents) {
            responseBuilder.append(saved).append('\n');
        }
        String response = responseBuilder.toString();

        if (NotificationEvent.fromQms(source)) {
            return Api.UniversalEvents().getQmsEvents(response);
        } else if (NotificationEvent.fromTheme(source)) {
            return Api.UniversalEvents().getFavoritesEvents(response);
        }
        return new ArrayList<>();
    }

    private void saveEvents(List<NotificationEvent> loadedEvents, NotificationEvent.Source source) {
        String prefKey = "";
        if (NotificationEvent.fromQms(source)) {
            prefKey = Preferences.Notifications.Data.QMS_EVENTS;
        } else if (NotificationEvent.fromTheme(source)) {
            prefKey = Preferences.Notifications.Data.FAVORITES_EVENTS;
        }

        Set<String> savedEvents = new ArraySet<>();
        for (NotificationEvent event : loadedEvents) {
            savedEvents.add(event.getSourceEventText());
        }
        App.get().getPreferences().edit().putStringSet(prefKey, savedEvents).apply();
    }

    private List<NotificationEvent> compareEvents(List<NotificationEvent> savedEvents, List<NotificationEvent> loadedEvents, NotificationEvent event, NotificationEvent.Source source) {
        List<NotificationEvent> resultEvents = new ArrayList<>();

        boolean onlyImportant = false;
        if (NotificationEvent.fromTheme(source)) {
            onlyImportant = Preferences.Notifications.Favorites.isOnlyImportant();
        }

        for (NotificationEvent loaded : loadedEvents) {
            boolean isNew = true;
            for (NotificationEvent saved : savedEvents) {
                if (loaded.getSourceId() == saved.getSourceId()) {
                    if (loaded.getTimeStamp() <= saved.getTimeStamp()) {
                        isNew = false;
                    }
                }
            }

            if (onlyImportant) {
                if (!(event != null && event.isMention() && event.getSourceId() == loaded.getSourceId())) {
                    if (!loaded.isImportant()) {
                        isNew = false;
                    }
                }
            }

            if (isNew) {
                resultEvents.add(loaded);
            }
        }

        return resultEvents;
    }

    private void loadEvents(Consumer<List<NotificationEvent>> consumer, NotificationEvent.Source source) {
        Observable<List<NotificationEvent>> observable = null;
        if (source == NotificationEvent.Source.QMS) {
            observable = Observable.fromCallable(() -> Api.UniversalEvents().getQmsEvents());
        } else if (source == NotificationEvent.Source.THEME) {
            observable = Observable.fromCallable(() -> Api.UniversalEvents().getFavoritesEvents());
        }

        if (observable != null) {
            observable
                    .onErrorReturnItem(new ArrayList<>())
                    .subscribeOn(Schedulers.io())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(consumer);
        }
    }

    public Bitmap loadAvatar(NotificationEvent event) throws Exception {
        Bitmap bitmap = null;
        if (!event.fromSite()) {
            ForumUser forumUser = ForumUsersCache.getUserById(event.getUserId());
            Log.d(LOG_TAG, "Forum user from cache " + forumUser);
            if (forumUser == null) {
                forumUser = ForumUsersCache.loadUserByNick(event.getUserNick());
                Log.d(LOG_TAG, "Forum user from network " + forumUser);
            }

            if (forumUser != null) {
                bitmap = ImageLoader.getInstance().loadImageSync(forumUser.getAvatar());
                Log.d(LOG_TAG, "Loaded avatar bitmap" + bitmap);
                if (bitmap != null) {
                    Log.d(LOG_TAG, "Bitmap h/w: " + bitmap.getHeight() + " : " + bitmap.getWidth());
                }
            }
        }

        return bitmap;
    }

    public void notifyTabs(TabNotification event) {
        switch (event.getSource()) {
            case THEME:
                App.get().notifyFavorites(event);
                break;
            case QMS:
                App.get().notifyQms(event);
        }
    }

    public void sendNotification(NotificationEvent event, Bitmap avatar) {
        eventsHistory.put(event.notifyId(), event);


        String title = createTitle(event);
        String text = createContent(event);
        String summaryText = createSummary(event);

        NotificationCompat.BigTextStyle bigTextStyle = new NotificationCompat.BigTextStyle();
        bigTextStyle.setBigContentTitle(title);
        bigTextStyle.bigText(text);
        bigTextStyle.setSummaryText(summaryText);

        NotificationCompat.Builder builder;
        builder = new NotificationCompat.Builder(this);

        if (avatar != null && !event.fromSite()) {
            builder.setLargeIcon(avatar);
        }
        builder.setSmallIcon(createSmallIcon(event));

        builder.setContentTitle(title);
        builder.setContentText(text);
        builder.setStyle(bigTextStyle);


        Intent notifyIntent = new Intent(this, MainActivity.class);
        notifyIntent.setData(Uri.parse(createIntentUrl(event)));
        notifyIntent.setAction(Intent.ACTION_VIEW);
        PendingIntent notifyPendingIntent = PendingIntent.getActivity(this, 0, notifyIntent, 0);
        builder.setContentIntent(notifyPendingIntent);

        builder.setAutoCancel(true);

        builder.setPriority(NotificationCompat.PRIORITY_DEFAULT);
        builder.setCategory(NotificationCompat.CATEGORY_SOCIAL);


        int defaults = 0;
        if (Preferences.Notifications.Main.isSoundEnabled()) {
            defaults |= NotificationCompat.DEFAULT_SOUND;
        }
        if (Preferences.Notifications.Main.isVibrationEnabled()) {
            defaults |= NotificationCompat.DEFAULT_VIBRATE;
        }
        if (Preferences.Notifications.Main.isIndicatorEnabled()) {
            defaults |= NotificationCompat.DEFAULT_LIGHTS;
        }
        builder.setDefaults(defaults);

        mNotificationManager.cancel(event.notifyId());
        mNotificationManager.notify(event.notifyId(), builder.build());
    }

    public void sendNotification(NotificationEvent event) {
        if (event.getUserId() == ClientHelper.getUserId()) {
            return;
        }

        if (Preferences.Notifications.Main.isAvatarsEnabled()) {
            Observable.fromCallable(() -> loadAvatar(event))
                    .onErrorReturn(throwable -> ImageLoader.getInstance().loadImageSync("assets://av.png"))
                    .map(bitmap -> {
                        if (bitmap != null) {
                            Resources res = App.getContext().getResources();
                            int height = (int) res.getDimension(android.R.dimen.notification_large_icon_height);
                            int width = (int) res.getDimension(android.R.dimen.notification_large_icon_width);
                            boolean isCircle = Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP;

                            bitmap = BitmapUtils.centerCrop(bitmap, width, height, 1.0f);
                            bitmap = BitmapUtils.createAvatar(bitmap, width, height, isCircle);
                        }
                        return bitmap;
                    })
                    .subscribeOn(Schedulers.io())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(avatar -> sendNotification(event, avatar));
        } else {
            sendNotification(event, null);
        }
    }


    public void sendNotifications(List<NotificationEvent> events) {
        if (events.size() == 0) {
            return;
        }
        if (events.size() == 1) {
            sendNotification(events.get(0));
            return;
        }
        // WebSocketEvent webSocketEvent = notificationEvent.getWebSocketEvent();


        String title = createStackedTitle(events);
        CharSequence text = createStackedContent(events);
        String summaryText = createStackedSummary(events);

        NotificationCompat.BigTextStyle bigTextStyle = new NotificationCompat.BigTextStyle();
        bigTextStyle.setBigContentTitle(title);
        bigTextStyle.bigText(text);
        bigTextStyle.setSummaryText(summaryText);

        NotificationCompat.MessagingStyle messagingStyle = new NotificationCompat.MessagingStyle("SU4KA");
        messagingStyle.setConversationTitle("CONV TITLE");
        for (NotificationEvent event : events) {
            messagingStyle.addMessage(event.getSourceTitle(), event.getTimeStamp(), event.getUserNick());
        }


        NotificationCompat.Builder mBuilder;
        mBuilder = new NotificationCompat.Builder(this);


        mBuilder.setSmallIcon(createStackedSmallIcon(events));

        mBuilder.setContentTitle(title);
        mBuilder.setContentText(text);
        mBuilder.setStyle(bigTextStyle);


        Intent notifyIntent = new Intent(this, MainActivity.class);
        notifyIntent.setData(Uri.parse(createStackedIntentUrl(events)));
        notifyIntent.setAction(Intent.ACTION_VIEW);
        PendingIntent notifyPendingIntent = PendingIntent.getActivity(this, 0, notifyIntent, 0);
        mBuilder.setContentIntent(notifyPendingIntent);

        mBuilder.setAutoCancel(true);

        mBuilder.setPriority(NotificationCompat.PRIORITY_DEFAULT);
        mBuilder.setCategory(NotificationCompat.CATEGORY_SOCIAL);
        mBuilder.setDefaults(NotificationCompat.DEFAULT_LIGHTS | NotificationCompat.DEFAULT_VIBRATE | NotificationCompat.DEFAULT_SOUND);

        int id = 0;
        NotificationEvent event = events.get(0);
        if (event.fromQms()) {
            id = NOTIFY_STACKED_QMS_ID;
        } else if (event.fromTheme()) {
            id = NOTIFY_STACKED_FAV_ID;
        }
        mNotificationManager.notify(id, mBuilder.build());
    }


    /*
    * DEFAULT EVENT
    * */

    @DrawableRes
    public int createSmallIcon(NotificationEvent event) {
        if (event.fromQms())
            return R.drawable.ic_notify_qms;

        if (event.fromTheme()) {
            if (event.isMention())
                return R.drawable.ic_notify_mention;

            return R.drawable.ic_notify_favorites;
        }

        if (event.fromSite())
            return R.drawable.ic_notify_site;

        return R.drawable.ic_notify_qms;
    }

    public String createTitle(NotificationEvent event) {
        if (event.fromQms()) {
            String nick = event.getUserNick();
            if (nick == null || nick.isEmpty())
                return "Сообщения 4PDA";
        }

        if (event.fromSite())
            return "ForPDA";

        return event.getUserNick();
    }

    public String createContent(NotificationEvent event) {
        if (event.fromQms())
            return String.format(getString(R.string.notification_content_qms_Nick_Count), event.getSourceTitle(), event.getMsgCount());

        if (event.fromTheme()) {
            if (event.isMention())
                return String.format(getString(R.string.notification_content_mention_Title), event.getSourceTitle());

            return String.format(getString(R.string.notification_content_theme_Title), event.getSourceTitle());
        }

        if (event.fromSite())
            return getString(R.string.notification_content_news);

        return "";
    }

    public String createSummary(NotificationEvent event) {
        if (event.isMention())
            return getString(R.string.notification_summary_mention);

        if (event.fromQms())
            return getString(R.string.notification_summary_qms);

        if (event.fromTheme())
            return getString(R.string.notification_summary_fav);

        if (event.fromSite())
            return getString(R.string.notification_summary_comment);

        return "";
    }

    public String createIntentUrl(NotificationEvent event) {
        if (event.isMention()) {
            if (event.fromTheme())
                return "https://4pda.ru/forum/index.php?showtopic=" + event.getSourceId() + "&view=findpost&p=" + event.getMessageId();

            if (event.fromSite())
                return "https://4pda.ru/index.php?p=" + event.getSourceId() + "/#comment" + event.getMessageId();
        }

        if (event.fromQms())
            return "https://4pda.ru/forum/index.php?act=qms&mid=" + event.getUserId() + "&t=" + event.getSourceId();

        if (event.fromTheme())
            return "https://4pda.ru/forum/index.php?showtopic=" + event.getSourceId() + "&view=getnewpost";

        return "";
    }


    /*
    * STACKED EVENTS
    * */
    private String createStackedTitle(List<NotificationEvent> events) {
        return createStackedSummary(events);
    }

    private CharSequence createStackedContent(List<NotificationEvent> events) {
        StringBuilder content = new StringBuilder();

        final int maxCount = 4;
        int size = Math.min(events.size(), maxCount);
        for (int i = 0; i < size; i++) {
            NotificationEvent event = events.get(i);
            if (event.fromQms()) {
                content.append("<b>").append(event.getUserNick()).append("</b>");
                content.append(": ").append(event.getSourceTitle());
            } else if (event.fromTheme()) {
                content.append(event.getSourceTitle());
            }
            if (i < size - 1) {
                content.append("<br>");
            }
        }

        if (events.size() > size) {
            content.append("<br>");
            content.append("...и еще ").append(events.size() - size);
        }

        return Utils.spannedFromHtml(content.toString());
    }

    private String createStackedSummary(List<NotificationEvent> events) {
        return createSummary(events.get(0));
    }

    @DrawableRes
    public int createStackedSmallIcon(List<NotificationEvent> events) {
        return createSmallIcon(events.get(0));
    }

    private String createStackedIntentUrl(List<NotificationEvent> events) {
        NotificationEvent event = events.get(0);
        if (event.fromQms())
            return "https://4pda.ru/forum/index.php?act=qms";

        if (event.fromTheme())
            return "https://4pda.ru/forum/index.php?act=fav";

        return "";
    }
}
