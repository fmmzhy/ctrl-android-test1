package ba.ctrl.ctrltest1.bases.b0;

import java.util.ArrayList;

import ba.ctrl.ctrltest1.R;
import ba.ctrl.ctrltest1.bases.Base;
import ba.ctrl.ctrltest1.bases.BaseSettingsActivity;
import ba.ctrl.ctrltest1.bases.BaseTemplateActivity;
import ba.ctrl.ctrltest1.service.CtrlServiceContacter;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.Toast;

public class BaseActivity extends BaseTemplateActivity {
    private static final String TAG = "b0.BaseActivity";

    private Base base;

    private Context context;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.setLayoutR(R.layout.b0_base_activity);
        super.onCreate(savedInstanceState);

        base = super.getBase();

        context = super.getApplicationContext();

        /* IMPLEMENTATION OF THIS BASE TYPE 0 : */

        final CheckBox ckSendAsNotification = (CheckBox) findViewById(R.id.ckSendAsNotification);

        ((Button) findViewById(R.id.btnSend)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                EditText txtSendData = (EditText) findViewById(R.id.txtSendData);
                String val = txtSendData.getText().toString();

                // lets target just this Base...
                ArrayList<String> baseIds = new ArrayList<String>();
                baseIds.add(base.getBaseid());

                // Send it man!
                CtrlServiceContacter.taskSendData(context, val, ckSendAsNotification.isChecked(), baseIds, new CtrlServiceContacter.ContacterResponse() {
                    @Override
                    public void onResponse(boolean serviceReceived) {
                        if (!serviceReceived) {
                            Toast.makeText(context, "Service didn't respond, try again!", Toast.LENGTH_SHORT).show();
                        }
                    }
                });
            }
        });

        // If this is a re-creation of previously displayed activity
        if (savedInstanceState != null) {
            super.myLog("onCreate() re-creation of Activity, I should have data in bundle (or db depending on implementation).");
        }
        else {
            super.myLog("onCreate() fresh start");
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.b0_activity, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.action_settings) {
            Intent intent = new Intent(this, BaseSettingsActivity.class);
            intent.putExtra("baseid", base.getBaseid());
            startActivity(intent);
        }
        else {
            return super.onOptionsItemSelected(item);
        }

        return true;
    }

    @Override
    public void baseNewDataArrival(String baseId, String data) {
        super.baseNewDataArrival(baseId, data);

        super.getDataSource().markBaseDataSeen(base.getBaseid());

        // do sometihng about this new data arrival event
        EditText rd = (EditText) findViewById(R.id.txtReceivedData);
        rd.setText(data);
    }

    @Override
    public void baseNewConnectionStatus(String baseId, boolean connected) {
        super.baseNewConnectionStatus(baseId, connected);

        // do something about this new base status event
    }

}
