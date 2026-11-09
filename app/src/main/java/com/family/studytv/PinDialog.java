package com.family.studytv;

import android.app.Dialog;
import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

/**
 * 电视遥控器可操作的密码键盘（很多电视遥控器没有数字键，所以做成屏幕键盘）。
 */
public class PinDialog extends Dialog {

    public interface Listener {
        void onResult(boolean ok);
    }

    /** 返回 null / 空串表示校验通过，否则返回要显示的错误文案 */
    public interface Verifier {
        String check(String input);
    }

    private static final int MAX_LEN = 12;

    private final Listener listener;
    private final String title;
    private final Verifier verifier;
    private final StringBuilder input = new StringBuilder();
    private TextView tvDisplay;

    /** 管理密码 */
    public PinDialog(Context context, Listener listener) {
        this(context, "管理密码", new Verifier() {
            @Override
            public String check(String in) {
                return in.equals(Prefs.pin()) ? null : "密码错误，请重试";
            }
        }, listener);
    }

    public PinDialog(Context context, String title, Verifier verifier, Listener listener) {
        super(context, R.style.DialogTheme);
        this.title = title;
        this.verifier = verifier;
        this.listener = listener;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.dialog_pin);
        setCanceledOnTouchOutside(false);
        if (title != null) {
            setTitle(title);
        }

        tvDisplay = (TextView) findViewById(R.id.tvPinDisplay);
        updateDisplay();

        int[] digits = {R.id.btn1, R.id.btn2, R.id.btn3, R.id.btn4, R.id.btn5,
                R.id.btn6, R.id.btn7, R.id.btn8, R.id.btn9, R.id.btn0};
        for (int id : digits) {
            View v = findViewById(id);
            if (v != null) {
                v.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        if (input.length() < MAX_LEN) {
                            input.append(((Button) view).getText());
                            updateDisplay();
                        }
                    }
                });
            }
        }

        findViewById(R.id.btnClear).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                input.setLength(0);
                updateDisplay();
            }
        });

        findViewById(R.id.btnOk).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String err = verifier == null ? null : verifier.check(input.toString());
                boolean ok = err == null || err.length() == 0;
                if (listener != null) {
                    listener.onResult(ok);
                }
                if (ok) {
                    dismiss();
                } else {
                    input.setLength(0);
                    updateDisplay();
                    tvDisplay.setText(err);
                }
            }
        });

        findViewById(R.id.btnCancel).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (listener != null) {
                    listener.onResult(false);
                }
                dismiss();
            }
        });
    }

    private void updateDisplay() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < input.length(); i++) {
            sb.append("● ");
        }
        if (sb.length() == 0) {
            sb.append("_ _ _ _");
        }
        if (tvDisplay != null) {
            tvDisplay.setText(sb.toString());
        }
    }
}
