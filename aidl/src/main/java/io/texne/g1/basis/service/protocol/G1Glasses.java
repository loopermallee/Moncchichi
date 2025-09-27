package io.texne.g1.basis.service.protocol;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * Minimal stub implementation so AIDL parcelable compiles. Replace with real fields later.
 */
public class G1Glasses implements Parcelable {
    public G1Glasses() {}

    protected G1Glasses(Parcel in) {}

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
        // No-op for stub implementation
    }
}
