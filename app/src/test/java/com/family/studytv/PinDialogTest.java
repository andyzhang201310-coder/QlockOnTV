package com.family.studytv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import android.app.Activity;
import android.widget.TextView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * 密码框标题必须跟着调用方走：卸载码流程不能显示成「管理密码」，
 * 否则家长会去输管理密码，输错就以为防卸载坏了。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class PinDialogTest {

    private PinDialog show(String title) {
        Activity a = Robolectric.buildActivity(Activity.class).create().get();
        PinDialog d = new PinDialog(a, title, new PinDialog.Verifier() {
            @Override
            public String check(String in) {
                return null;
            }
        }, new PinDialog.Listener() {
            @Override
            public void onResult(boolean ok) {
            }
        });
        d.show();
        return d;
    }

    @Test
    public void uninstallDialogShowsItsOwnTitle() {
        TextView t = show("请输入卸载码").findViewById(R.id.tvPinTitle);
        assertNotNull("布局里必须有可复用的标题控件", t);
        assertEquals("请输入卸载码", t.getText().toString());
    }

    @Test
    public void adminDialogKeepsAdminTitle() {
        TextView t = show("请输入管理密码").findViewById(R.id.tvPinTitle);
        assertEquals("请输入管理密码", t.getText().toString());
    }
}
