package org.springframework.ldap.support.transaction;

import java.util.Stack;

import junit.framework.TestCase;

import org.easymock.MockControl;
import org.springframework.ldap.support.transaction.CompensatingTransactionRecordingOperation;
import org.springframework.ldap.support.transaction.CompensatingTransactionRollbackOperation;
import org.springframework.ldap.support.transaction.LdapCompensatingTransactionDataManager;

public class LdapCompensatingTransactionDataManagerTest extends TestCase {

    private MockControl recordingOperationControl;

    private CompensatingTransactionRecordingOperation recordingOperationMock;

    private MockControl rollbackOperationControl;

    private CompensatingTransactionRollbackOperation rollbackOperationMock;

    protected void setUp() throws Exception {
        super.setUp();
        recordingOperationControl = MockControl
                .createControl(CompensatingTransactionRecordingOperation.class);
        recordingOperationMock = (CompensatingTransactionRecordingOperation) recordingOperationControl
                .getMock();

        rollbackOperationControl = MockControl
                .createControl(CompensatingTransactionRollbackOperation.class);
        rollbackOperationMock = (CompensatingTransactionRollbackOperation) rollbackOperationControl
                .getMock();

    }

    protected void tearDown() throws Exception {
        super.tearDown();
        recordingOperationControl = null;
        recordingOperationMock = null;

        rollbackOperationControl = null;
        rollbackOperationMock = null;
    }

    protected void replay() {
        recordingOperationControl.replay();
        rollbackOperationControl.replay();
    }

    protected void verify() {
        recordingOperationControl.verify();
        rollbackOperationControl.verify();
    }

    public void testOperationPerformed() {
        LdapCompensatingTransactionDataManager tested = new LdapCompensatingTransactionDataManager(
                null) {
            protected CompensatingTransactionRecordingOperation getRecordingOperation(
                    String operation) {
                assertEquals("bind", operation);
                return recordingOperationMock;
            }
        };

        Object[] expectedParams = new Object[0];
        recordingOperationControl.expectAndReturn(recordingOperationMock
                .performOperation(expectedParams), rollbackOperationMock);

        replay();
        tested.operationPerformed("bind", expectedParams);
        verify();

        Stack result = tested.getRollbackOperations();
        assertFalse(result.isEmpty());
        assertSame(rollbackOperationMock, result.peek());
    }

    public void testRollback() {
        LdapCompensatingTransactionDataManager tested = new LdapCompensatingTransactionDataManager(null);
        tested.getRollbackOperations().push(rollbackOperationMock);
        
        rollbackOperationMock.rollback();
        
        replay();
        tested.rollback();
        verify();
    }

}
