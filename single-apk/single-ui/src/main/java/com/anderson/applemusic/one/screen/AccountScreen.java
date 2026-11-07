package com.anderson.applemusic.one.screen;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.anderson.applemusic.one.theme.Inter;
import com.anderson.applemusic.one.theme.Shapes;
import com.anderson.applemusic.one.theme.Tokens;
import com.anderson.applemusic.one.widget.CircleButton;
import com.anderson.applemusic.one.widget.Icons;
import com.anderson.applemusic.one.widget.Pill;

/**
 * Conta panel: explains that the Apple ID belongs to the installed Apple Music engine and
 * offers the official sign-in. The sign-in itself is still the engine's Compose AMS sheet
 * driven from {@code OneActivity}; this panel only gives it a proper entry point.
 */
public final class AccountScreen {

    private final ScreenHost host;
    private final FrameLayout root;

    public AccountScreen(Context context, final ScreenHost host) {
        this.host = host;

        root = new FrameLayout(context);
        root.setBackgroundColor(0xD9000000);
        root.setVisibility(View.GONE);
        root.setClickable(true);
        root.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                host.closeAccount();
            }
        });

        LinearLayout panel = new LinearLayout(context);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER_HORIZONTAL);
        panel.setPadding(Tokens.dp(24), Tokens.dp(26), Tokens.dp(24), Tokens.dp(20));
        panel.setBackground(Shapes.rounded(Tokens.SURFACE, Tokens.RADIUS_SHEET));
        panel.setClickable(true);
        Shapes.clip(panel, Tokens.RADIUS_SHEET);

        CircleButton icon = new CircleButton(context, Icons.ACCOUNT, 56f,
                Tokens.ACCENT_DIM, Tokens.TEXT, 28f);
        icon.setFocusable(false);
        icon.setClickable(false);
        panel.addView(icon, new LinearLayout.LayoutParams(Tokens.dp(56), Tokens.dp(56)));

        TextView title = Inter.text(context, "Conta", Tokens.SECTION, Inter.BOLD, Tokens.TEXT);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleParams.topMargin = Tokens.dp(14);
        panel.addView(title, titleParams);

        TextView body = Inter.text(context,
                "O Apple ID e a biblioteca pertencem ao Apple Music instalado. "
                        + "Entre na sua conta para ver biblioteca, estações e recomendações.",
                Tokens.BODY, Inter.REGULAR, Tokens.TEXT_DIM);
        body.setGravity(Gravity.CENTER);
        body.setLineSpacing(Tokens.dp(4), 1f);
        LinearLayout.LayoutParams bodyParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bodyParams.topMargin = Tokens.dp(8);
        panel.addView(body, bodyParams);

        Pill signIn = Pill.accent(context, Icons.ACCOUNT, "Entrar na conta Apple");
        signIn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                host.closeAccount();
                host.openAccount();
            }
        });
        LinearLayout.LayoutParams signInParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        signInParams.topMargin = Tokens.dp(20);
        panel.addView(signIn, signInParams);

        Pill closePanel = Pill.highlighted(context, 0, "Fechar");
        closePanel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                host.closeAccount();
            }
        });
        LinearLayout.LayoutParams closeParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        closeParams.topMargin = Tokens.dp(10);
        panel.addView(closePanel, closeParams);

        TextView hint = Inter.text(context, "O login é o mesmo do app oficial; ao terminar, "
                + "esta tela é recarregada.", Tokens.CAPTION, Inter.REGULAR, Tokens.TEXT_DIM);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, Tokens.dp(14), 0, 0);
        panel.addView(hint);

        int width = Math.min(Tokens.dp(460), Math.round(Tokens.screenWidth() * 0.88f));
        FrameLayout.LayoutParams panelParams = new FrameLayout.LayoutParams(
                width, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        root.addView(panel, panelParams);
    }

    public View view() {
        return root;
    }

    public void open() {
        root.setVisibility(View.VISIBLE);
    }

    public void close() {
        root.setVisibility(View.GONE);
    }

    public boolean isOpen() {
        return root.getVisibility() == View.VISIBLE;
    }
}
