/* Copyright 2018 Urban Airship and Contributors */

package com.urbanairship.push;


import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.WorkerThread;
import android.support.v4.content.WakefulBroadcastReceiver;

import com.urbanairship.Autopilot;
import com.urbanairship.Logger;
import com.urbanairship.job.JobDispatcher;
import com.urbanairship.job.JobInfo;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;


/**
 * {@link PushProvider} callback methods.
 *
 * @hide
 */
public abstract class PushProviderBridge {


    final static String EXTRA_PROVIDER_CLASS = "EXTRA_PROVIDER_CLASS";
    final static String EXTRA_PUSH = "EXTRA_PUSH";

    /**
     * Triggers a registration update.
     *
     * @param context The application context.
     */
    public static void requestRegistrationUpdate(@NonNull Context context) {
        Autopilot.automaticTakeOff(context);

        JobInfo jobInfo = JobInfo.newBuilder()
                                 .setAction(PushManagerJobHandler.ACTION_UPDATE_PUSH_REGISTRATION)
                                 .setId(JobInfo.CHANNEL_UPDATE_PUSH_TOKEN)
                                 .setNetworkAccessRequired(true)
                                 .setAirshipComponent(PushManager.class)
                                 .build();

        JobDispatcher.shared(context).dispatch(jobInfo);
    }


    /**
     * Creates a new request to process an incoming push message.
     *
     * @param provider The provider's class.
     * @param pushMessage The push message.
     */
    @WorkerThread
    public static ProcessPushRequest processPush(@NonNull Class<? extends PushProvider> provider, @NonNull PushMessage pushMessage) {
        return new ProcessPushRequest(provider, pushMessage);
    }

    /**
     * Process push request.
     */
    public static class ProcessPushRequest {

        private final Class<? extends PushProvider> provider;
        private final PushMessage pushMessage;
        private boolean allowWakeLocks;
        private long maxCallbackWaitTime;


        private ProcessPushRequest(@NonNull Class<? extends PushProvider> provider, @NonNull PushMessage pushMessage) {
            this.provider = provider;
            this.pushMessage = pushMessage;
        }

        /**
         * Enables or disables the use of wakelocks. Defaults to {@code false}.
         *
         * @param allowWakeLocks {@code true} to allow wakelocks, otherwise {@code false}.
         * @return The process push request.
         */
        public ProcessPushRequest allowWakeLocks(boolean allowWakeLocks) {
            this.allowWakeLocks = allowWakeLocks;
            return this;
        }

        /**
         * Sets the max callback wait time in milliseconds.
         * @param milliseconds The max callback wait time. If <= 0, the callback will
         * wait until the push request is completed.
         * @return The process push request.
         */
        public ProcessPushRequest setMaxCallbackWaitTime(long milliseconds) {
            this.maxCallbackWaitTime = milliseconds;
            return this;
        }

        /**
         * Executes the request.
         *
         * @param context The application context.
         */
        public void execute(@NonNull Context context) {
            execute(context, null);
        }

        /**
         * Executes the request.
         *
         * @param context The application context.
         * @param callback The callback.
         */
        public void execute(@NonNull Context context, @Nullable final Runnable callback) {
            // If older than Android O or a high priority message try to start the push service
            if (Build.VERSION.SDK_INT < 26 || PushMessage.PRIORITY_HIGH.equals(pushMessage.getExtra(PushMessage.EXTRA_DELIVERY_PRIORITY, null))) {
                Intent intent = new Intent(context, PushService.class)
                        .setAction(PushService.ACTION_PROCESS_PUSH)
                        .putExtra(PushManager.EXTRA_PUSH_MESSAGE_BUNDLE, pushMessage.getPushBundle())
                        .putExtra(EXTRA_PROVIDER_CLASS, provider.toString());

                try {
                    if (allowWakeLocks) {
                        //noinspection deprecation
                        WakefulBroadcastReceiver.startWakefulService(context, intent);
                    } else {
                        context.startService(intent);
                    }

                    if (callback != null) {
                        callback.run();
                    }
                    return;
                } catch (SecurityException | IllegalStateException e) {
                    Logger.error("Unable to run push in the push service.", e);

                    if (allowWakeLocks) {
                        //noinspection deprecation
                        WakefulBroadcastReceiver.completeWakefulIntent(intent);
                    }
                }
            }

            // Otherwise fallback to running push in the executor
            IncomingPushRunnable.Builder pushRunnableBuilder = new IncomingPushRunnable.Builder(context)
                    .setMessage(pushMessage)
                    .setProviderClass(provider.toString());

            Future<?> future = PushManager.PUSH_EXECUTOR.submit(pushRunnableBuilder.build());

            try {
                if (maxCallbackWaitTime > 0) {
                    future.get(maxCallbackWaitTime, TimeUnit.MILLISECONDS);
                } else {
                    future.get();
                }
            } catch (TimeoutException e) {
                Logger.error("Application took too long to process push. App may get closed.");
            } catch (Exception e) {
                Logger.error("Failed to wait for notification", e);
            }

            if (callback != null) {
                callback.run();
            }

        }

        /**
         * Executes the request synchronously.
         *
         * @param context The application context.
         */
        @WorkerThread
        public void executeSync(@NonNull Context context) {
            final CountDownLatch countDownLatch = new CountDownLatch(1);
            execute(context, new Runnable() {
                @Override
                public void run() {
                    countDownLatch.countDown();
                }
            });

            try {
                countDownLatch.await();
            } catch (InterruptedException e) {
                Logger.error("Failed to wait for push.", e);
            }
        }
    }

}
