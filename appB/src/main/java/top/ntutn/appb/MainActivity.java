package top.ntutn.appb;

import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

public class MainActivity extends AppCompatActivity {
    private static final String METHOD_GET_DATA = "get_data";
    private static final String AUTHORITY = "top.ntutn.appa.provider";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        Button button = findViewById(R.id.button);
        TextView resultTextView = findViewById(R.id.resultTextView);

        button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // Use call() method to access the ContentProvider from appA
                Uri uri = Uri.parse("content://" + AUTHORITY);

                try {
                    // Call the method in the ContentProvider
                    android.os.Bundle result = getContentResolver().call(
                        uri,
                        METHOD_GET_DATA,
                        null,
                        null
                    );

                    if (result != null) {
                        String message = result.getString("message");
                        String data = result.getString("data");
                        String status = result.getString("status");

                        StringBuilder output = new StringBuilder();
                        output.append("Message: ").append(message).append("\n");
                        output.append("Data: ").append(data).append("\n");
                        output.append("Status: ").append(status);

                        resultTextView.setText(output.toString());
                    } else {
                        resultTextView.setText("No result returned from ContentProvider call()");
                    }
                } catch (Exception e) {
                    resultTextView.setText("Error calling ContentProvider: " + e.getMessage());
                    Toast.makeText(MainActivity.this, "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                }
            }
        });
    }
}