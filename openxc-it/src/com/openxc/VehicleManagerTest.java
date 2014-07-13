package com.openxc;

import java.net.URI;

import junit.framework.Assert;
import android.content.Intent;
import android.test.ServiceTestCase;
import android.test.suitebuilder.annotation.MediumTest;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;
import org.mockito.Mockito;
import org.mockito.ArgumentCaptor;

import com.openxc.measurements.EngineSpeed;
import com.openxc.measurements.Measurement;
import com.openxc.measurements.SteeringWheelAngle;
import com.openxc.measurements.TurnSignalStatus;
import com.openxc.measurements.UnrecognizedMeasurementTypeException;
import com.openxc.measurements.VehicleSpeed;
import com.openxc.messages.MessageKey;
import com.openxc.messages.Command;
import com.openxc.messages.Command.CommandType;
import com.openxc.messages.DiagnosticRequest;
import com.openxc.messages.SimpleVehicleMessage;
import com.openxc.messages.NamedVehicleMessage;
import com.openxc.messages.VehicleMessage;
import com.openxc.remote.VehicleService;
import com.openxc.remote.VehicleServiceException;
import com.openxc.sinks.VehicleDataSink;
import com.openxc.sources.DataSourceException;
import com.openxc.sources.TestSource;
import com.openxc.interfaces.VehicleInterfaceDescriptor;
import com.openxc.interfaces.VehicleInterface;
import com.openxc.interfaces.usb.UsbVehicleInterface;
import com.openxc.interfaces.bluetooth.BluetoothVehicleInterface;
import com.openxc.interfaces.network.NetworkVehicleInterface;
import com.openxc.sinks.DataSinkException;

public class VehicleManagerTest extends ServiceTestCase<VehicleManager> {
    VehicleManager service;
    VehicleSpeed speedReceived;
    SteeringWheelAngle steeringAngleReceived;
    String receivedMessageId;
    TestSource source = new TestSource();
    VehicleMessage messageReceived;
    VehicleInterface mTestInterface;

    VehicleMessage.Listener messageListener = new VehicleMessage.Listener() {
        public void receive(VehicleMessage message) {
            messageReceived = message;
        }
    };

    VehicleSpeed.Listener speedListener = new VehicleSpeed.Listener() {
        public void receive(Measurement measurement) {
            speedReceived = (VehicleSpeed) measurement;
        }
    };

    SteeringWheelAngle.Listener steeringWheelListener =
            new SteeringWheelAngle.Listener() {
        public void receive(Measurement measurement) {
            steeringAngleReceived = (SteeringWheelAngle) measurement;
        }
    };

    public VehicleManagerTest() {
        super(VehicleManager.class);
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();

        mTestInterface = mock(VehicleInterface.class);
        when(mTestInterface.isConnected()).thenReturn(true);
        speedReceived = null;
        steeringAngleReceived = null;

        // if the service is already running (and thus may have old data
        // cached), kill it.
        getContext().stopService(new Intent(getContext(),
                    VehicleService.class));
    }

    // Due to bugs and or general crappiness in the ServiceTestCase, you will
    // run into many unexpected problems if you start the service in setUp - see
    // this blog post for more details:
    // http://convales.blogspot.de/2012/07/never-start-or-shutdown-service-in.html
    private void prepareServices() {
        Intent startIntent = new Intent();
        startIntent.setClass(getContext(), VehicleManager.class);
        service = ((VehicleManager.VehicleBinder)
                bindService(startIntent)).getService();
        service.waitUntilBound();
        service.addSource(source);
        service.addLocalVehicleInterface(mTestInterface);
    }

    @Override
    protected void tearDown() throws Exception {
        if(source != null) {
            source.stop();
        }
        super.tearDown();
    }

    @MediumTest
    public void testGetNoData() throws UnrecognizedMeasurementTypeException {
        prepareServices();
        try {
            service.get(EngineSpeed.class);
        } catch(NoValueException e) {
            return;
        }
        Assert.fail("Expected a NoValueException");
    }

    @MediumTest
    public void testListenForMessage() throws VehicleServiceException,
            UnrecognizedMeasurementTypeException {
        prepareServices();
        service.addListener(new NamedVehicleMessage("foo").getKey(),
                messageListener);
        source.inject("foo", 42.0);
        assertNotNull(messageReceived);
        assertEquals(messageReceived.asNamedMessage().getName(), "foo");
    }

    @MediumTest
    public void testListenForMeasurement() throws VehicleServiceException,
            UnrecognizedMeasurementTypeException {
        prepareServices();
        service.addListener(VehicleSpeed.class, speedListener);
        source.inject(VehicleSpeed.ID, 42.0);
        assertNotNull(speedReceived);
    }

    @MediumTest
    public void testCustomSink() throws DataSourceException {
        prepareServices();
        assertNull(receivedMessageId);
        service.addSink(mCustomSink);
        source.inject(VehicleSpeed.ID, 42.0);
        assertNotNull(receivedMessageId);
        service.removeSink(mCustomSink);
        receivedMessageId = null;
        source.inject(VehicleSpeed.ID, 42.0);
        assertNull(receivedMessageId);
    }

    @MediumTest
    public void testAddListenersTwoMeasurements()
            throws VehicleServiceException,
            UnrecognizedMeasurementTypeException {
        prepareServices();
        service.addListener(VehicleSpeed.class, speedListener);
        service.addListener(SteeringWheelAngle.class, steeringWheelListener);
        source.inject(VehicleSpeed.ID, 42.0);
        source.inject(SteeringWheelAngle.ID, 12.1);
        TestUtils.pause(5);
        assertNotNull(steeringAngleReceived);
        assertNotNull(speedReceived);
    }

    @MediumTest
    public void testRemoveMessageListener() throws VehicleServiceException,
            UnrecognizedMeasurementTypeException {
        prepareServices();
        MessageKey key = new NamedVehicleMessage("foo").getKey();
        service.addListener(key, messageListener);
        source.inject("foo", 42.0);
        messageReceived = null;
        service.removeListener(key, messageListener);
        source.inject("foo", 42.0);
        assertNull(messageReceived);
    }

    @MediumTest
    public void testRemoveMeasurementListener() throws VehicleServiceException,
            UnrecognizedMeasurementTypeException {
        prepareServices();
        service.addListener(VehicleSpeed.class, speedListener);
        source.inject(VehicleSpeed.ID, 42.0);
        service.removeListener(VehicleSpeed.class, speedListener);
        speedReceived = null;
        source.inject(VehicleSpeed.ID, 42.0);
        TestUtils.pause(10);
        assertNull(speedReceived);
    }

    @MediumTest
    public void testRemoveWithoutListening()
            throws VehicleServiceException,
            UnrecognizedMeasurementTypeException {
        prepareServices();
        service.removeListener(VehicleSpeed.class, speedListener);
    }

    @MediumTest
    public void testRemoveOneMeasurementListener()
            throws VehicleServiceException,
            UnrecognizedMeasurementTypeException {
        prepareServices();
        service.addListener(VehicleSpeed.class, speedListener);
        service.addListener(SteeringWheelAngle.class, steeringWheelListener);
        source.inject(VehicleSpeed.ID, 42.0);
        service.removeListener(VehicleSpeed.class, speedListener);
        speedReceived = null;
        source.inject(VehicleSpeed.ID, 42.0);
        TestUtils.pause(10);
        assertNull(speedReceived);
    }

    @MediumTest
    public void testConsistentAge()
            throws UnrecognizedMeasurementTypeException,
            NoValueException, VehicleServiceException, DataSourceException {
        prepareServices();
        source.inject(VehicleSpeed.ID, 42.0);
        TestUtils.pause(1);
        Measurement measurement = service.get(VehicleSpeed.class);
        long age = measurement.getAge();
        assertTrue("Measurement age (" + age + ") should be > 5ms",
                age > 5);
    }

    @MediumTest
    public void testSendMeasurement() throws
            UnrecognizedMeasurementTypeException, DataSinkException {
        prepareServices();
        service.send(new TurnSignalStatus(
                    TurnSignalStatus.TurnSignalPosition.LEFT));
        verify(mTestInterface).receive(Mockito.any(VehicleMessage.class));
    }

    @MediumTest
    public void testSendMessage() throws DataSinkException {
        prepareServices();
        service.send(new SimpleVehicleMessage("foo", "bar"));
        verify(mTestInterface).receive(Mockito.any(VehicleMessage.class));
    }

    @MediumTest
    public void testSendDiagnosticRequest() throws DataSinkException {
        prepareServices();
        DiagnosticRequest request = new DiagnosticRequest(1, 2, 3, 4);
        service.send(request);
        ArgumentCaptor<Command> argument = ArgumentCaptor.forClass(
                Command.class);
        verify(mTestInterface).receive(argument.capture());
        Command command = argument.getValue();
        assertEquals(command.getCommand(), Command.CommandType.DIAGNOSTIC_REQUEST);
        assertNotNull(command.getDiagnosticRequest());
        assertThat(command.getDiagnosticRequest(), equalTo(request));
    }

    @MediumTest
    public void testGetMeasurement() throws UnrecognizedMeasurementTypeException,
            NoValueException {
        prepareServices();
        source.inject(VehicleSpeed.ID, 42.0);
        VehicleSpeed measurement = (VehicleSpeed)
                service.get(VehicleSpeed.class);
        assertNotNull(measurement);
        assertEquals(measurement.getValue().doubleValue(), 42.0);
    }

    @MediumTest
    public void testNoDataAfterRemoveSource() {
        prepareServices();
        service.addListener(new NamedVehicleMessage("foo").getKey(),
                messageListener);
        service.removeSource(source);
        source.inject("foo", 42.0);
        assertNull(messageReceived);
    }

    @MediumTest
    public void testUsbInterfaceEnabledByDefault()
            throws VehicleServiceException {
        prepareServices();
        assertThat(service.getActiveSources(),
                hasItem(UsbVehicleInterface.class));
    }

    @MediumTest
    public void testAddVehicleInterfaceByClass() throws VehicleServiceException {
        prepareServices();
        service.addVehicleInterface(UsbVehicleInterface.class, "");
        assertThat(service.getActiveSources(),
                hasItem(UsbVehicleInterface.class));
        // Not a whole lot we can test without an actual device attached and
        // without being able to mock the interface class out in the remote
        // process where the VehicleSevice runs, but at least we know this
        // method didn't explode.
    }

    @MediumTest
    public void testAddVehicleInterfaceByClassWithResource()
            throws VehicleServiceException {
        prepareServices();
        service.addVehicleInterface(NetworkVehicleInterface.class,
                "localhost:8080");
        assertThat(service.getActiveSources(),
                hasItem(NetworkVehicleInterface.class));
    }

    @MediumTest
    public void testAddBluetoothVehicleInterface()
            throws VehicleServiceException {
        prepareServices();
        service.addVehicleInterface(BluetoothVehicleInterface.class,
                "00:01:02:03:04:05");
        // If the running on an emulator it will report  that it doesn't have a
        // Bluetooth adapter, and we will be unable to construct the
        // BluetoothVehicleInterface interface.
        // assertThat(service.getActiveSources(),
                // hasItem(new VehicleInterfaceDescriptor(
                        // BluetoothVehicleInterface.class, false)));
    }

    @MediumTest
    public void testRemoveVehicleInterfaceByClass()
            throws VehicleServiceException {
        prepareServices();
        service.addVehicleInterface(UsbVehicleInterface.class, "");
        service.removeVehicleInterface(UsbVehicleInterface.class);
        assertThat(service.getActiveSources(),
                not(hasItem(UsbVehicleInterface.class)));
    }

    @MediumTest
    public void testRemoveVehicleInterfaceByClassWithoutAdding()
            throws VehicleServiceException {
        prepareServices();
        service.removeVehicleInterface(UsbVehicleInterface.class);
    }

    @MediumTest
    public void testToString() {
        prepareServices();
        assertThat(service.toString(), notNullValue());
    }

    // TODO set bluetooth polling
    // TODO get source summaries
    // TODO get sink summaries
    // TODO get message count
    // TODO get local vehicle interface
    // TODO remove local vehicle interface

    private VehicleDataSink mCustomSink = new VehicleDataSink() {
        public void receive(VehicleMessage message) {
            receivedMessageId = ((NamedVehicleMessage)message).getName();
        }

        public void stop() { }
    };
}

