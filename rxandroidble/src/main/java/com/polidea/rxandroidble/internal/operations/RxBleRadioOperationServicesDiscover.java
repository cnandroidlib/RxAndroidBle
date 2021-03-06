package com.polidea.rxandroidble.internal.operations;

import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattService;
import android.support.annotation.NonNull;
import com.polidea.rxandroidble.RxBleDeviceServices;
import com.polidea.rxandroidble.exceptions.BleGattCannotStartException;
import com.polidea.rxandroidble.exceptions.BleGattOperationType;
import com.polidea.rxandroidble.internal.RxBleRadioOperation;
import com.polidea.rxandroidble.internal.connection.RxBleGattCallback;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import rx.Observable;
import rx.Scheduler;
import rx.Subscription;

public class RxBleRadioOperationServicesDiscover extends RxBleRadioOperation<RxBleDeviceServices> {

    private final RxBleGattCallback rxBleGattCallback;

    private final BluetoothGatt bluetoothGatt;

    private final long timeout;

    private final TimeUnit timeoutTimeUnit;

    private final Scheduler timeoutScheduler;

    public RxBleRadioOperationServicesDiscover(
            RxBleGattCallback rxBleGattCallback,
            BluetoothGatt bluetoothGatt,
            long timeout,
            TimeUnit timeoutTimeUnit,
            Scheduler timeoutScheduler) {
        this.rxBleGattCallback = rxBleGattCallback;
        this.bluetoothGatt = bluetoothGatt;
        this.timeout = timeout;
        this.timeoutTimeUnit = timeoutTimeUnit;
        this.timeoutScheduler = timeoutScheduler;
    }

    @Override
    protected void protectedRun() {

        //noinspection Convert2MethodRef
        final Subscription subscription = rxBleGattCallback
                .getOnServicesDiscovered()
                .first()
                .timeout(timeout, timeoutTimeUnit, timeoutFallbackProcedure(), timeoutScheduler)
                .doOnTerminate(() -> releaseRadio())
                .subscribe(getSubscriber());

        final boolean success = bluetoothGatt.discoverServices();
        if (!success) {
            subscription.unsubscribe();
            onError(new BleGattCannotStartException(BleGattOperationType.SERVICE_DISCOVERY));
        }
    }

    /**
     * Sometimes it happens that the {@link BluetoothGatt} will receive all {@link BluetoothGattService}'s,
     * {@link android.bluetooth.BluetoothGattCharacteristic}'s and {@link android.bluetooth.BluetoothGattDescriptor}
     * but it won't receive the final callback that the service discovery was completed. This is a potential workaround.
     *
     * There is a change in Android 7.0.0_r1 where all data is received at once - in this situation returned services size will be always 0
     * https://android.googlesource.com/platform/frameworks/base/+/android-7.0.0_r1/core/java/android/bluetooth/BluetoothGatt.java#206
     * https://android.googlesource.com/platform/frameworks/base/+/android-6.0.1_r72/core/java/android/bluetooth/BluetoothGatt.java#205
     *
     * @return Observable that may emit {@link RxBleDeviceServices} or {@link TimeoutException}
     */
    @NonNull
    private Observable<RxBleDeviceServices> timeoutFallbackProcedure() {
        return Observable.defer(() -> {
            final List<BluetoothGattService> services = bluetoothGatt.getServices();
            if (services.size() == 0) {
                // if after the timeout services are empty we have no other option to declare a failed discovery
                return Observable.error(new TimeoutException());
            } else {
                /*
                it is observed that usually the Android OS is returning services, characteristics and descriptors in a short period of time
                if there are some services available we will wait for 5 more seconds just to be sure that
                the timeout was not triggered right in the moment of filling the services and then emit a value.
                 */
                return Observable
                        .timer(5, TimeUnit.SECONDS, timeoutScheduler)
                        .flatMap(t -> Observable.fromCallable(() -> new RxBleDeviceServices(bluetoothGatt.getServices())));
            }
        });
    }
}
