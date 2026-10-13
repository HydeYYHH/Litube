package com.hhst.youtubelite.player.sponsor;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
import androidx.annotation.Keep;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.Objects;
import org.junit.internal.TextListener;
import org.junit.runner.JUnitCore;
import org.junit.runner.Result;

/** Runs JUnit against the optimized application. */
public final class R8VerificationRunner extends Instrumentation {
    private Bundle arguments;

    // Preserve the Objects overloads required by the shared desugared runtime.
    @Keep public static Object requireNonNull(Object value, String message) {
        return Objects.requireNonNull(value, message);
    }

    @Keep public static Object requireNonNull(Object value) {
        return Objects.requireNonNull(value);
    }

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        this.arguments = arguments == null ? new Bundle() : arguments;
        start();
    }

    @Override public void onStart() {
        Bundle summary = new Bundle();
        try {
            String[] names = arguments.getString("class", SponsorBlockMinifiedAndroidTest.class.getName()).split(",");
            Class<?>[] classes = new Class<?>[names.length];
            for (int i = 0; i < names.length; i++) classes[i] = getContext().getClassLoader().loadClass(names[i]);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            JUnitCore core = new JUnitCore();
            core.addListener(new TextListener(new PrintStream(output, true, "UTF-8")));
            Result result = core.run(classes);
            summary.putString("stream", "\n" + output.toString("UTF-8"));
            finish(result.wasSuccessful() ? Activity.RESULT_OK : Activity.RESULT_CANCELED, summary);
        } catch (Throwable failure) {
            summary.putString("stream", "\nR8 verification runner failed: " + failure + "\n");
            finish(Activity.RESULT_CANCELED, summary);
        }
    }
}
