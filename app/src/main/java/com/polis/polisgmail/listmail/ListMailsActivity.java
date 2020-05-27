package com.polis.polisgmail.listmail;

import android.Manifest;
import android.accounts.AccountManager;
import android.app.Dialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.AsyncTask;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;
import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.tasks.Task;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.snackbar.Snackbar;
import com.google.api.client.extensions.android.http.AndroidHttp;
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential;
import com.google.api.client.googleapis.extensions.android.gms.auth.GooglePlayServicesAvailabilityIOException;
import com.google.api.client.googleapis.extensions.android.gms.auth.UserRecoverableAuthIOException;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.client.repackaged.org.apache.commons.codec.binary.Base64;
import com.google.api.client.util.ExponentialBackOff;
import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.GmailScopes;
import com.google.api.services.gmail.model.Label;
import com.google.api.services.gmail.model.ListMessagesResponse;
import com.google.api.services.gmail.model.Message;
import com.polis.polisgmail.App;
import com.polis.polisgmail.login.LoginActivity;
import com.polis.polisgmail.MailLab;
import com.polis.polisgmail.R;
import com.polis.polisgmail.dao.Mail;
import com.polis.polisgmail.di.CompositionRoot;
import com.polis.polisgmail.listmail.listeners.OnRefresh;
import com.polis.polisgmail.sendmail.InternetDetector;
import com.polis.polisgmail.sendmail.SendMailActivity;
import com.polis.polisgmail.sendmail.Utils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

import javax.mail.MessagingException;
import javax.mail.Session;
import javax.mail.internet.MimeMessage;

public class ListMailsActivity extends AppCompatActivity implements
        View.OnClickListener {

    private SwipeRefreshLayout swipeRefreshLayout;
    private RecyclerView mailRecyclerView;
    private MailAdapter mailAdapter;
    private MailPresenter mailPresenter;
    private Button mailsButton;
    private FloatingActionButton floatingActionButton;
    private static final String PREF_ACCOUNT_NAME = "accountName";
    GoogleAccountCredential mCredential;
    ProgressDialog mProgress;
    private InternetDetector internetDetector;
    private static final String[] SCOPES = {
            GmailScopes.GMAIL_LABELS,
            GmailScopes.GMAIL_COMPOSE,
            GmailScopes.GMAIL_INSERT,
            GmailScopes.GMAIL_MODIFY,
            GmailScopes.GMAIL_READONLY,
            GmailScopes.MAIL_GOOGLE_COM
    };
    private CompositionRoot compositionRoot;

    private MailLab mailLab;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        init();
        mailsButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                getResultsFromApi(view);
            }
        });
    }

    public void init() {
        internetDetector = new InternetDetector(getApplicationContext());
        mProgress = new ProgressDialog(this);
        compositionRoot = ((App) getApplication()).getCompositionRoot();

        mailLab = compositionRoot.getMailLab();

        swipeRefreshLayout = findViewById(R.id.mail_list_refresh);
        mailRecyclerView = findViewById(R.id.mail_recycler_view);
        floatingActionButton = findViewById(R.id.floatingActionButton);

        floatingActionButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(ListMailsActivity.this, SendMailActivity.class);
                startActivity(intent);
            }
        });

        mCredential = GoogleAccountCredential.usingOAuth2(
                getApplicationContext(), Arrays.asList(SCOPES))
                .setBackOff(new ExponentialBackOff());

        mailsButton = findViewById(R.id.mails);
        findViewById(R.id.move_to_loginactivity_button).setOnClickListener(this);

        LinearLayoutManager linearLayoutManager = new LinearLayoutManager(this);
        linearLayoutManager.setOrientation(LinearLayoutManager.VERTICAL);

        mailAdapter = new MailAdapter(mailLab.getMails());

        swipeRefreshLayout.setOnRefreshListener(new OnRefresh(swipeRefreshLayout, mailAdapter));
        mailRecyclerView.setLayoutManager(linearLayoutManager);
        mailRecyclerView.setAdapter(mailAdapter);

        MailModel mailModel = new MailModel(mailLab);
        mailPresenter = new MailPresenter(mailModel);
        mailPresenter.attachView(this);
        mailPresenter.loadMails();
    }
    private void getResultsFromApi(View view) {
        if (!isGooglePlayServicesAvailable()) {
            acquireGooglePlayServices();
        } else if (mCredential.getSelectedAccountName() == null) {
            mCredential.setSelectedAccountName(getIntent().getExtras().getString("SendEmail"));
            Log.v("accountdata2", getIntent().getExtras().getString("SendEmail"));
            getResultsFromApi(view);
        } else if (!internetDetector.checkMobileInternetConn()) {
            showMessage(view, "No network connection available.");
        } else {
            new MakeRequestTask(this, mCredential).execute();
        }
    }

    private boolean isGooglePlayServicesAvailable() {
        GoogleApiAvailability apiAvailability = GoogleApiAvailability.getInstance();
        int connectionStatusCode = apiAvailability.isGooglePlayServicesAvailable(this);
        return connectionStatusCode == ConnectionResult.SUCCESS;
    }

    //    // Method to Show Info, If Google Play Service is Not Available.
    private void acquireGooglePlayServices() {
        GoogleApiAvailability apiAvailability = GoogleApiAvailability.getInstance();
        int connectionStatusCode = apiAvailability.isGooglePlayServicesAvailable(this);
        if (apiAvailability.isUserResolvableError(connectionStatusCode)) {
            showGooglePlayServicesAvailabilityErrorDialog(connectionStatusCode);
        }
    }

    public void showMails(List<Mail> mails) {
        mailAdapter.setMails(mails);
    }

    public void onClick(View view) {
        if (view.getId()==R.id.move_to_loginactivity_button) {
            Intent intent = new Intent(this, LoginActivity.class);
            startActivity(intent);
        }

    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mailPresenter.detachView();
    }



    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        switch (requestCode) {
            case Utils.REQUEST_GOOGLE_PLAY_SERVICES:
                if (resultCode != RESULT_OK) {
                    showMessage(mailsButton, "This app requires Google Play Services. Please install " +
                            "Google Play Services on your device and relaunch this app.");
                } else {
                    getResultsFromApi(mailsButton);
                }
                break;
            case Utils.REQUEST_ACCOUNT_PICKER:
                if (resultCode == RESULT_OK && data != null && data.getExtras() != null) {
                    String accountName = data.getStringExtra(AccountManager.KEY_ACCOUNT_NAME);
                    if (accountName != null) {
                        SharedPreferences settings = getPreferences(Context.MODE_PRIVATE);
                        SharedPreferences.Editor editor = settings.edit();
                        editor.putString(PREF_ACCOUNT_NAME, accountName);
                        editor.apply();
                        mCredential.setSelectedAccountName(accountName);
                        getResultsFromApi(mailsButton);
                    }
                }
                break;
            case Utils.REQUEST_AUTHORIZATION:
                if (resultCode == RESULT_OK) {
                    getResultsFromApi(mailsButton);
                }
                break;
        }
    }



    // Async Task for sending Mail using GMail OAuth
    private class MakeRequestTask extends AsyncTask<Void, Void, String> {

        private com.google.api.services.gmail.Gmail mService = null;
        private Exception mLastError = null;
        private View view = mailsButton;
        private ListMailsActivity activity;

        MakeRequestTask(ListMailsActivity activity, GoogleAccountCredential credential) {
            HttpTransport transport = AndroidHttp.newCompatibleTransport();
            JsonFactory jsonFactory = JacksonFactory.getDefaultInstance();
            mService = new com.google.api.services.gmail.Gmail.Builder(
                    transport, jsonFactory, credential)
                    .setApplicationName(getResources().getString(R.string.app_name))
                    .build();
            this.activity = activity;
        }

        @Override
        protected String doInBackground(Void... params) {
            try {
                return getDataFromApi();
            } catch (Exception e) {
                mLastError = e;
                cancel(true);
                return null;
            }
        }

        private String getDataFromApi() throws IOException, JSONException, MessagingException {
            String user = "me";
            List<String> LabelIds = new ArrayList<>();
            LabelIds.add("INBOX");
            List<Message> messages;
            messages = listMessagesWithLabels(mService, user, LabelIds);
            JSONArray jArray = new JSONArray(messages.toString());
            Log.v("tag before for",  "123");
            //for (int i=0; i < jArray.length(); i++)
            for (int i=0; i < 50; i++)
            {
                try {
                    JSONObject parsedID = jArray.getJSONObject(i);
                    // Pulling items from the array
                    String parsedMessage = parsedID.getString("id");
                    getMessage(mService, user, parsedMessage);
                    Log.v("Parsed Message",  parsedMessage);

                } catch (JSONException e) {
                    Log.v("trynotsuccess",  ":(");
                    // Oops
                }
            }
            MimeMessage email = getMimeMessage(mService, user, "1452302f78b8ed4e");
            Log.v("mimemessage1",  email.toString());
            getLabel(mService, user, "INBOX");
            Log.v("messages",  Integer.toString(messages.size()));
            String response = "";
            return response;

        }

        public void getLabel(Gmail service, String userId, String labelId)
                throws IOException {
            Label label = service.users().labels().get(userId, labelId).execute();
            Log.v("label", label.getName());
            //System.out.println("Label " + label.getName() + " retrieved.");
            //System.out.println(label.toPrettyString());
        }

        private Message getMessage(Gmail service, String userId, String messageId)
                throws IOException {
            Message message = service.users().messages().get(userId, messageId).execute();

            System.out.println("Message snippet: " + message.getSnippet());

            return message;
        }

        private MimeMessage getMimeMessage(Gmail service, String userId, String messageId)
                throws IOException, MessagingException {
            Message message = service.users().messages().get(userId, messageId).setFormat("raw").execute();

            Base64 base64Url = new Base64(true);
            byte[] emailBytes = base64Url.decodeBase64(message.getRaw());

            Properties props = new Properties();
            Session session = Session.getDefaultInstance(props, null);

            MimeMessage email = new MimeMessage(session, new ByteArrayInputStream(emailBytes));

            return email;
        }
        // Method to receive email
        private List<Message> listMessagesWithLabels(Gmail service, String userId,
                                                           List<String> labelIds) throws IOException {
            ListMessagesResponse response = service.users().messages().list(userId)
                    .setLabelIds(labelIds).execute();

            List<Message> messages = new ArrayList<Message>();
            while (response.getMessages() != null) {
                messages.addAll(response.getMessages());
                if (response.getNextPageToken() != null) {
                    String pageToken = response.getNextPageToken();
                    response = service.users().messages().list(userId).setLabelIds(labelIds)
                            .setPageToken(pageToken).execute();
                } else {
                    break;
                }
            }

            for (Message message : messages) {
                System.out.println(message.toPrettyString());
            }

            return messages;
        }

        @Override
        protected void onPreExecute() {
            mProgress.show();
        }

        @Override
        protected void onPostExecute(String output) {
            mProgress.hide();
            if (output == null || output.length() == 0) {
                showMessage(view, "No results returned.");
            } else {
                showMessage(view, output);
            }
        }

        @Override
        protected void onCancelled() {
            mProgress.hide();
            if (mLastError != null) {
                if (mLastError instanceof GooglePlayServicesAvailabilityIOException) {
                    showGooglePlayServicesAvailabilityErrorDialog(
                            ((GooglePlayServicesAvailabilityIOException) mLastError)
                                    .getConnectionStatusCode());
                } else if (mLastError instanceof UserRecoverableAuthIOException) {
                    startActivityForResult(
                            ((UserRecoverableAuthIOException) mLastError).getIntent(),
                            Utils.REQUEST_AUTHORIZATION);
                } else {
                    showMessage(view, "The following error occurred:\n" + mLastError);
                    Log.v("Error", mLastError + "");
                }
            } else {
                showMessage(view, "Request Cancelled.");
            }
        }
    }
    void showGooglePlayServicesAvailabilityErrorDialog(final int connectionStatusCode) {
        GoogleApiAvailability apiAvailability = GoogleApiAvailability.getInstance();
        Dialog dialog = apiAvailability.getErrorDialog(
                ListMailsActivity.this,
                connectionStatusCode,
                Utils.REQUEST_GOOGLE_PLAY_SERVICES);
        dialog.show();
    }
    private void showMessage(View view, String message) {
        Snackbar.make(view, message, Snackbar.LENGTH_LONG).show();
    }
}