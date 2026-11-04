package dev.tinyvault;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.TypedArray;
import android.graphics.Typeface;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricPrompt;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.text.*;
import android.view.*;
import android.view.inputmethod.EditorInfo;
import android.view.textclassifier.TextClassifier;
import android.widget.*;
import android.window.*;
import java.io.IOException;
import java.nio.file.*;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

public final class MainActivity extends Activity {
    // text field that does not give suggestions
    private static final int TEXT = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
    // close sensitive data after two minutes 
    private static final int CLOSE_MS = 120_000;
    // size of input fields
    private static final int CAPACITY = 1024;
    // alias for the key thats stored inside the keystore
    private static final String KEY = "note";
    // ensure that we dont create a new buffer when a new editable is requested,
    // rather we reuse the existing one
    private static final Editable.Factory KEEP = new Editable.Factory() {
        @Override public Editable newEditable(CharSequence source) { return source instanceof Editable kept ? kept : super.newEditable(source); }
    };

    // used for back operations when we are not on the main screen
    private final OnBackInvokedCallback back = this::list;
    // executed after the 2 minutes
    private final Runnable close = this::list;
    // the page container
    private LinearLayout page;
    // padding for container
    private int gutter;
    // decrypted bytes where we store after decrypting
    private char[] open = {};
    // dialog for various purposes
    private AlertDialog dialog;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        // ensure this app is not allowed to be screen recorded or screen shotted
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        page = new LinearLayout(this); // create new container
        page.setOrientation(LinearLayout.VERTICAL); // make it vertical
        gutter = Math.round(16 * getResources().getDisplayMetrics().density); // calculate padding
        page.setPadding(gutter, gutter, gutter, gutter); // set padding for container
        page.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS); // dont allow auto fill
        page.setImportantForContentCapture(View.IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS); // no content capture 
        page.setAccessibilityDataSensitive(View.ACCESSIBILITY_DATA_SENSITIVE_YES); // mark data in this app as sensitive
        ScrollView scroll = new ScrollView(this); // this app is scrollable
        scroll.setFitsSystemWindows(true);
        scroll.setFilterTouchesWhenObscured(true);
        scroll.addView(page); // add the page
        setContentView(scroll); // show the page
        list(); // main function
    }

    @Override
    protected void onStop() {
        list();
        super.onStop();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // here we create two buttons which can generate random cryptographically secure
        // passwords for everything from passphrases to seeds.
        TypedArray bar = getActionBar().getThemedContext().obtainStyledAttributes(new int[] {android.R.attr.textColorPrimary});
        action(menu, "RNG", android.R.drawable.ic_lock_lock, bar.getColorStateList(0), this::generate);
        action(menu, "New note", android.R.drawable.ic_menu_add, bar.getColorStateList(0), this::add);
        bar.recycle(); // hand back the typed array
        return true;
    }

    private void action(Menu menu, String label, int icon, ColorStateList tint, Runnable action) {
        menu.add(label).setIcon(icon).setIconTintList(tint).setShowAsActionFlags(MenuItem.SHOW_AS_ACTION_ALWAYS)
                .setOnMenuItemClickListener(item -> { action.run(); return true; });
    }

    // the main function and main view
    private void list() {
        blank(); // clear everything from before
        getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(back); // remove the callback handler
        // since the back button should now exit the app.
        String[] names = fileList(); // get the list of files in the app
        Arrays.sort(names); // sort alphabetically
        if (names.length == 0) say("No notes yet. Tap + to add one."); // tell user to add note if empty
        for (String name : names) {
            if (name.endsWith(".part")) continue; // ignore partially written files.
            String title = title(name); // extract the filename
            button(title, () -> unlock(name, title)); // add a button for each filename which allows decryption
        }
    }

    // extracts a title from an encrypted file
    private String title(String name) {
        try { return Vault.title(Files.readAllBytes(path(name))); } catch (Exception damaged) { return "(unreadable) " + name; }
    }

    // decrypts an encrypted from its given name and title
    private void unlock(String name, String title) {
        blank(); // clear everything
        say(title); // write the title
        TextView status = say("Unlocking...");
        button("Close", this::list); // button if user wants to go back
        try {
            byte[] file = Files.readAllBytes(path(name)); // read the file
            Cipher aes = Cipher.getInstance("AES/GCM/NoPadding"); // get the cipher used for the notes
            aes.init(Cipher.DECRYPT_MODE, key(), Vault.nonce(file)); // reconstruct the cipher from the key and nonce
            // prompt the user if they want to open the file, it will run on a separate thread and after unlock will show to the user
            prompt(aes, "Open " + title, status, authorised -> run(() -> Vault.open(authorised, file), plain -> show(name, title, plain), status));
        } catch (Exception failure) {
            status.setText("x " + failure);
        }
    }


    // show some plain bytes to the user
    private void show(String name, String title, byte[] plain) {
        blank(); // clear everything from before
        open = Vault.chars(plain); // extract plain chars from bytes
        say(title); // write title
        TextView body = new TextView(this); // create new text holder
        body.setText(open, 0, open.length); // render the decrypted chars
        page.addView(body); // add it to the page
        button("Close", this::list); // add close button
        // allow user to delete the note if they wanted to
        Button delete = button("Hold to delete", () -> say("hold the button down to confirm"));
        // add click listener which will delete the file if they hold it for long time.
        delete.setOnLongClickListener(held -> { deleteFile(name); list(); return true; });
        // call the close function within the 2 minutes
        page.postDelayed(close, CLOSE_MS);
    }

    // add a new encrypted note
    private void add() {
        blank();
        EditText title = field("Title", TEXT); // allow user to add title
        EditText content = field("Content", TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE); // and add content to be encrypted
        content.setMinLines(6); // make the content box slightly taller
        button("Encrypt", () -> { // add button to encrypt
            if (title.length() == 0 || content.length() == 0) { // require text and title
                say("x title and content are both needed");
                return;
            }
            TextView status = say("Encrypting..."); // update status
            try {
                Cipher aes = Cipher.getInstance("AES/GCM/NoPadding"); // get aes cipher
                aes.init(Cipher.ENCRYPT_MODE, key()); // initialize cipher on encrypt mode and get_or_init the key
                prompt(aes, "Encrypt the new note", status, authorised -> { // prompt the user to encrypt the note
                    String name = title.getText().toString();
                    byte[] text = utf8(content);
                    run(() -> save(Vault.seal(authorised, name, text)), saved -> list(), status); // execute encryption on separate thread then show list again
                });
            } catch (Exception failure) {
                status.setText("x " + failure);
            }
        });
    }

    // a functionality to generate strong cryptographically secure strings for whatever purpose
    private void generate() {
        char[] fresh = Vault.password(); // generate fresh password
        TextView view = new TextView(this); // create new text view
        view.setText(fresh, 0, fresh.length); // set the length to be the length of gen:ed password
        view.setTextSize(22); // set font size
        view.setTypeface(Typeface.MONOSPACE); // set font
        view.setGravity(Gravity.CENTER); // position
        view.setPadding(gutter, gutter, gutter, gutter); // add padding
        view.setAccessibilityDataSensitive(View.ACCESSIBILITY_DATA_SENSITIVE_YES); // prevent accessibility features 
        view.setImportantForContentCapture(View.IMPORTANT_FOR_CONTENT_CAPTURE_NO); // prevent content capture
        dialog = new AlertDialog.Builder(this).setTitle("New password: (72bit)").setView(view) // create a popup dialog
                .setNeutralButton("Regenerate", (d, which) -> generate()) // add regenerate button
                .setNegativeButton("Close", null) // add close button
                .setOnDismissListener(d -> Arrays.fill(fresh, '\0')).create(); // zeroize the fresh variable with zeros after closing window
        dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        dialog.show(); // show the modal
    }

    private void prompt(Cipher aes, String what, TextView status, Consumer<Cipher> then) {
        new BiometricPrompt.Builder(this).setTitle("tinyvault").setDescription(what) // create prompt which authenticates the user device wide
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG | BiometricManager.Authenticators.DEVICE_CREDENTIAL) // allow strong biometric or device credential
                .build().authenticate(new BiometricPrompt.CryptoObject(aes), new CancellationSignal(), getMainExecutor(), // then we get the crypto oboject.
                        new BiometricPrompt.AuthenticationCallback() {
                            @Override
                            public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                                if (status.isAttachedToWindow()) then.accept(result.getCryptoObject().getCipher());
                            }

                            @Override
                            public void onAuthenticationError(int code, CharSequence message) {
                                status.setText("x " + message);
                            }
                        });
    }

    // gets or initializes a new keystore
    private SecretKey key() throws GeneralSecurityException, IOException {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); // get the key store
        store.load(null);
        if (store.containsAlias(KEY)) return (SecretKey) store.getKey(KEY, null); // if the store already contains our key then we can return it
        KeyGenerator maker = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore"); // otherwise we init a new aes key
        maker.init(new KeyGenParameterSpec.Builder(KEY, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT) // set key purposes
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256) // set key size an props
                .setUserAuthenticationRequired(true) // force auth
                .setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG | KeyProperties.AUTH_DEVICE_CREDENTIAL) // set the allowed auth methods
                .setInvalidatedByBiometricEnrollment(false)
                .setUnlockedDeviceRequired(true) // require unlocked device
                .setIsStrongBoxBacked(getPackageManager().hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)) // require a strongbox keystore for this
                .build());
        return maker.generateKey(); // generate key and return to caller
    }

    // runs some task on a separate thread
    private <T> void run(Callable<T> work, Consumer<T> then, TextView status) {
        new Thread(() -> {
            try {
                T out = work.call(); // run the task
                runOnUiThread(() -> { // then hand back to UI
                    if (status.isAttachedToWindow()) then.accept(out);
                    else if (out instanceof byte[] secret) Arrays.fill(secret, (byte) 0);
                });
            } catch (Exception failure) {
                runOnUiThread(() -> status.setText(failure instanceof AEADBadTagException
                        ? "x sealed by a key this phone no longer has, or damaged" : "x " + failure));
            }
        }).start();
    }

    private void blank() {
        page.removeCallbacks(close);
        if (dialog != null) dialog.dismiss();
        Arrays.fill(open, '\0');
        for (int i = 0; i < page.getChildCount(); i++) {
            if (page.getChildAt(i) instanceof EditText field) {
                Editable text = field.getText();
                text.replace(0, text.length(), new String(new char[text.length()]));
                text.replace(0, text.length(), new String(new char[2 * CAPACITY - 1]));
            }
        }
        page.removeAllViews();
        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, back);
    }

    // adds a simple text view with some text
    private TextView say(String line) {
        TextView view = new TextView(this);
        view.setText(line);
        page.addView(view);
        return view;
    }

    // create a button with some text and associate an action with it
    private Button button(String label, Runnable action) {
        Button button = new Button(this);
        button.setText(label);
        button.setOnClickListener(clicked -> action.run());
        page.addView(button);
        return button;
    }

    // create an editable field input type
    private EditText field(String label, int inputType) {
        say(label); // write the label for the field
        EditText field = new EditText(this); // create field
        field.setEditableFactory(KEEP); // ensure only one buffer is used
        field.setText(new char[CAPACITY], 0, CAPACITY); // set the maximum capactiy for the field
        field.getText().clear(); // clear the field
        field.setInputType(inputType); // set requested input type
        field.setImeOptions(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING); // prevent android from using the input
        field.setTextClassifier(TextClassifier.NO_OP); // no help for this type of input
        page.addView(field);
        return field;
    }

    // convert some fields input text to raw bytes
    private static byte[] utf8(EditText field) {
        char[] chars = new char[field.length()];
        field.getText().getChars(0, chars.length, chars, 0);
        return Vault.bytes(chars);
    }

    // save some bytes to the disk of the application
    private Path save(byte[] file) throws IOException {
        String name = String.valueOf(System.currentTimeMillis()); // the note filename is the timestamp
        Files.write(path(name + ".part"), file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, StandardOpenOption.SYNC); // write it as a .part file
        return Files.move(path(name + ".part"), path(name), StandardCopyOption.ATOMIC_MOVE); // then do an atomic rename
    }

    // gets the path for some filename
    private Path path(String name) {
        return getFilesDir().toPath().resolve(name);
    }
}
