package com.noop.mgecg;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Handles the two notification buttons. */
public class DayLogReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        String a = i == null ? null : i.getAction();
        if (DayLogService.ACTION_MARK.equals(a)) {
            DayLog.addEvent(c, "marker", "from notification");
            DayLogService.refresh(c);
        } else if (DayLogService.ACTION_PULL.equals(a)) {
            MainActivity.requestPull();
        } else if (EcgReminder.isAction(a)) {          // 0.42.0: reading reminders and their snooze
            EcgReminder.onAction(c, a);
        } else if (IntakeNotifier.kindOf(a) != null) {          // 0.38.0: Drink, Carb meal, Caffeine from the intake notification
            IntakeNotifier.onAction(c, a);
        }
    }
}
