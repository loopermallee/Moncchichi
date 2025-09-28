package io.texne.g1.basis.service.protocol;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * Parcelable representation of a discovered G1 glasses device exposed by the AIDL service.
 */
public class G1Glasses implements Parcelable {
    public static final int UNINITIALIZED = 0;
    public static final int DISCONNECTED = 1;
    public static final int CONNECTING = 2;
    public static final int CONNECTED = 3;
    public static final int DISCONNECTING = 4;
    public static final int ERROR = -1;

    private String id;
    private String name;
    private int connectionState = UNINITIALIZED;
    private int batteryPercentage = -1;
    private String firmwareVersion;

    public G1Glasses() {
    }

    protected G1Glasses(Parcel in) {
        id = in.readString();
        name = in.readString();
        connectionState = in.readInt();
        batteryPercentage = in.readInt();
        firmwareVersion = in.readString();
    }

    public static final Creator<G1Glasses> CREATOR = new Creator<G1Glasses>() {
        @Override
        public G1Glasses createFromParcel(Parcel in) {
            return new G1Glasses(in);
        }

        @Override
        public G1Glasses[] newArray(int size) {
            return new G1Glasses[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(id);
        dest.writeString(name);
        dest.writeInt(connectionState);
        dest.writeInt(batteryPercentage);
        dest.writeString(firmwareVersion);
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public int getConnectionState() {
        return connectionState;
    }

    public void setConnectionState(int connectionState) {
        this.connectionState = connectionState;
    }

    public int getBatteryPercentage() {
        return batteryPercentage;
    }

    public void setBatteryPercentage(int batteryPercentage) {
        this.batteryPercentage = batteryPercentage;
    }

    public String getFirmwareVersion() {
        return firmwareVersion;
    }

    public void setFirmwareVersion(String firmwareVersion) {
        this.firmwareVersion = firmwareVersion;
    }
}
