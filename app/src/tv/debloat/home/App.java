package tv.debloat.home;

import android.app.Application;
import android.content.Context;

public final class App extends Application {
    static Context ctx;

    @Override public void onCreate() {
        super.onCreate();
        ctx = this;
        Fonts.load(this);
        Buttons.seedDefaults(this);
    }
}
