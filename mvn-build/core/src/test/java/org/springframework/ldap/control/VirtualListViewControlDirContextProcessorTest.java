/*
 * Copyright 2005-2007 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.ldap.control;

import java.io.IOException;

import javax.naming.ldap.Control;
import javax.naming.ldap.LdapContext;

import junit.framework.AssertionFailedError;
import junit.framework.TestCase;

import org.easymock.MockControl;
import org.springframework.ldap.OperationNotSupportedException;

import com.sun.jndi.ldap.Ber;
import com.sun.jndi.ldap.BerDecoder;
import com.sun.jndi.ldap.BerEncoder;
import com.sun.jndi.ldap.ctl.SortControl;
import com.sun.jndi.ldap.ctl.VirtualListViewControl;
import com.sun.jndi.ldap.ctl.VirtualListViewResponseControl;

/**
 * Unit tests for the VirtualListViewControlDirContextProcessor class.
 * 
 * @author Ulrik Sandberg
 */
public class VirtualListViewControlDirContextProcessorTest extends TestCase {

	private static final String OID_REQUEST = "2.16.840.1.113730.3.4.9";

	private static final String OID_RESPONSE = "2.16.840.1.113730.3.4.10";

	private MockControl ldapContextControl;

	private LdapContext ldapContextMock;

	protected void setUp() throws Exception {
		super.setUp();

		// Create ldapContext mock
		ldapContextControl = MockControl.createControl(LdapContext.class);
		ldapContextMock = (LdapContext) ldapContextControl.getMock();
	}

	protected void tearDown() throws Exception {
		super.tearDown();

		ldapContextControl = null;
		ldapContextMock = null;
	}

	protected void replay() {
		ldapContextControl.replay();
	}

	protected void verify() {
		ldapContextControl.verify();
	}

	public void testPreProcess() throws Exception {
		final VirtualListViewControl control = new VirtualListViewControl(0, 3,
				true);
		int pageSize = 5;
		VirtualListViewControlDirContextProcessor tested = new VirtualListViewControlDirContextProcessor(
				pageSize) {
			public Control createRequestControl() {
				return control;
			}
		};

		ldapContextControl.expectAndReturn(
				ldapContextMock.getRequestControls(), new Control[0]);

		SortControl sortControl = new SortControl(new String[] { "cn" }, true);
		VirtualListViewControl vlvControl = new VirtualListViewControl(0, 0, 0,
				0, true);

		Control[] controls = new Control[] { sortControl, vlvControl };
		ldapContextMock.setRequestControls(controls);
		// just check that class names match
		ldapContextControl.setMatcher(new ControlArrayMatcher());

		replay();

		tested.preProcess(ldapContextMock);

		verify();
	}

	public void testCreateRequestControlWithTargetAsOffset() throws Exception {
		int pageSize = 5;
		int targetOffset = 25;
		int listSize = 1000;
		VirtualListViewControlDirContextProcessor tested = new VirtualListViewControlDirContextProcessor(
				pageSize, targetOffset, listSize,
				new VirtualListViewResultsCookie(new byte[0], 0, 0));
		VirtualListViewControl result = (VirtualListViewControl) tested
				.createRequestControl();
		assertNotNull(result);
		assertEquals(OID_REQUEST, result.getID());

		// verify that the values have been encoded as we expect
		int expectedBeforeCount = 0;
		int expectedAfterCount = 4;
		int expectedOffset = 25;
		int expectedContentCount = listSize;
		assertEncodedRequest(result.getEncodedValue(), expectedBeforeCount,
				expectedAfterCount, expectedOffset, expectedContentCount,
				new byte[0]);
	}

	public void testCreateRequestControlWithTargetAsPercentage()
			throws Exception {
		int pageSize = 5;
		int targetPercentage = 25;
		int listSize = 1000;
		VirtualListViewControlDirContextProcessor tested = new VirtualListViewControlDirContextProcessor(
				pageSize, targetPercentage, listSize,
				new VirtualListViewResultsCookie(new byte[0], 0, 0));
		tested.setOffsetPercentage(true);
		VirtualListViewControl result = (VirtualListViewControl) tested
				.createRequestControl();
		assertNotNull(result);
		assertEquals(OID_REQUEST, result.getID());

		int expectedBeforeCount = 2;
		int expectedAfterCount = 2;
		// interestingly, it seems rather than calculate what 25% of 1000 is,
		// the VLVControl requests 25 out of an expected 100
		int expectedOffset = 25;
		int expectedContentCount = 100;
		assertEncodedRequest(result.getEncodedValue(), expectedBeforeCount,
				expectedAfterCount, expectedOffset, expectedContentCount,
				new byte[0]);
	}

	public void testPostProcess() throws Exception {
		int pageSize = 5;
		int targetOffset = 25;
		int listSize = 1000;
		VirtualListViewControlDirContextProcessor tested = new VirtualListViewControlDirContextProcessor(
				pageSize, targetOffset, listSize,
				new VirtualListViewResultsCookie(new byte[0], 0, 0));

		int virtualListViewResult = 53; // unwilling to perform
		byte[] encoded = encodeResponseValue(10, listSize,
				virtualListViewResult);
		VirtualListViewResponseControl control = new VirtualListViewResponseControl(
				OID_RESPONSE, false, encoded);
		ldapContextControl.expectAndDefaultReturn(ldapContextMock
				.getResponseControls(), new Control[] { control });

		replay();

		try {
			tested.postProcess(ldapContextMock);
			fail("OperationNotSupportedException expected");
		}
		catch (OperationNotSupportedException expected) {
			Throwable cause = expected.getCause();
			assertEquals(javax.naming.OperationNotSupportedException.class,
					cause.getClass());
			assertEquals("[LDAP: error code 53 - Unwilling To Perform]", cause
					.getMessage());
		}
		verify();
		assertNotNull(tested.getCookie());
		assertEquals(0, tested.getCookie().getCookie().length);
	}

	public void testBerDecoding() throws Exception {
		int virtualListViewResult = 53; // unwilling to perform
		byte[] encoded = encodeResponseValue(10, 1000, virtualListViewResult);

		int expectedLength = 14;
		assertEncodedResponse(encoded, expectedLength, 10, 1000, 53,
				new byte[0]);
	}

	private byte[] encodeResponseValue(int targetPosition, int contentCount,
			int virtualListViewResult) throws IOException {

		// build the ASN.1 encoding
		BerEncoder ber = new BerEncoder(10);

		ber.beginSeq(Ber.ASN_SEQUENCE | Ber.ASN_CONSTRUCTOR);
		ber.encodeInt(targetPosition); // list offset for the target entry
		ber.encodeInt(contentCount); // server's estimate of the current
		// number of entries in the list
		ber.encodeInt(virtualListViewResult, Ber.ASN_ENUMERATED);
		ber.encodeOctetString(new byte[0], Ber.ASN_OCTET_STR);
		ber.endSeq();

		return ber.getTrimmedBuf();
	}

	private void assertEncodedRequest(byte[] encodedValue,
			int expectedBeforeCount, int expectedAfterCount,
			int expectedOffset, int expectedContentCount,
			byte[] expectedContextId) throws Exception {
		dumpEncodedValue("VirtualListViewRequest\n", encodedValue);
		BerDecoder ber = new BerDecoder(encodedValue, 0, encodedValue.length);
		ber.parseSeq(null);

		int actualBeforeCount = ber.parseInt();
		int actualAfterCount = ber.parseInt();
		byte targetType = (byte) ber.parseByte();
		targetType <<= 3; // skip highest three bits
		targetType >>= 3;
		ber.parseLength(); // ignore
		switch (targetType) {
		case 0: // byOffset
			int actualOffset = ber.parseInt();
			int actualContentCount = ber.parseInt();
			assertEquals("beforeCount,", expectedBeforeCount, actualBeforeCount);
			assertEquals("afterCount,", expectedAfterCount, actualAfterCount);
			assertEquals("offset,", expectedOffset, actualOffset);
			assertEquals("contentCount,", expectedContentCount,
					actualContentCount);
			break;

		case 1: // greaterThanOrEqual
			throw new AssertionFailedError(
					"CHOICE value greaterThanOrEqual not supported");

		default:
			throw new AssertionFailedError("illegal CHOICE value: "
					+ targetType);
		}
		byte[] bs = ber.parseOctetString(Ber.ASN_OCTET_STR, null);
		assertContextId(expectedContextId, bs);
	}

	private void assertContextId(byte[] expectedContextId,
			byte[] actualContextId) {
		if (expectedContextId == null && actualContextId == null) {
			return;
		}
		if (expectedContextId == null && actualContextId != null) {
			fail("expected <null>, got <" + actualContextId + ">");
		}
		if (expectedContextId != null && actualContextId == null) {
			fail("expected <" + expectedContextId + ">, got <null>");
		}
		assertEquals(expectedContextId.length, actualContextId.length);
	}

	private void assertEncodedResponse(byte[] encodedValue,
			int expectedEncodingLength, int expectedTargetPosition,
			int expectedContentCount, int expectedVirtualListViewResult,
			byte[] expectedContextId) throws Exception {
		dumpEncodedValue("VirtualListViewResponse\n", encodedValue);
		assertEquals(expectedEncodingLength, encodedValue.length);
		BerDecoder ber = new BerDecoder(encodedValue, 0, encodedValue.length);
		ber.parseSeq(null);

		int actualTargetPosition = ber.parseInt();
		int actualContentCount = ber.parseInt();
		int actualVirtualListViewResult = ber.parseEnumeration();
		assertEquals("targetPosition,", expectedTargetPosition,
				actualTargetPosition);
		assertEquals("contentCount,", expectedContentCount, actualContentCount);
		assertEquals("virtualListViewResult,", expectedVirtualListViewResult,
				actualVirtualListViewResult);
		byte[] bs = ber.parseOctetString(Ber.ASN_OCTET_STR, null);
		assertContextId(expectedContextId, bs);
	}

	private void dumpEncodedValue(String message, byte[] encodedValue) {
		Ber.dumpBER(System.out, message, encodedValue, 0, encodedValue.length);
	}
}
