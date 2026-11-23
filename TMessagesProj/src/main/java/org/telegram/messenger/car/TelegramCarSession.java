package org.telegram.messenger.car;

import android.content.Intent;

import androidx.annotation.NonNull;
import androidx.car.app.Screen;
import androidx.car.app.Session;

import org.telegram.messenger.FileLog;

public class TelegramCarSession extends Session {

    @NonNull
    @Override
    public Screen onCreateScreen(@NonNull Intent intent) {
        // Shows in the app's own log (cache/logs) which car entry point the host opened.
        FileLog.d("CarApp: templated session started, host api " + getCarContext().getCarAppApiLevel());
        return new HomeScreen(getCarContext());
    }
}
