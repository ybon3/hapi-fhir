package ca.uhn.fhir.jpa.dao.r4;

import ca.uhn.fhir.jpa.dao.SearchParameterMap;
import ca.uhn.fhir.jpa.search.StaleSearchDeletingSvcImpl;
import ca.uhn.fhir.jpa.util.StopWatch;
import ca.uhn.fhir.rest.api.server.IBundleProvider;
import ca.uhn.fhir.rest.param.StringParam;
import org.apache.commons.lang3.Validate;
import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.r4.model.Patient;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallbackWithoutResult;
import org.springframework.transaction.support.TransactionTemplate;

import static ca.uhn.fhir.jpa.util.TestUtil.sleepAtLeast;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.junit.Assert.*;

public class FhirResourceDaoR4SearchPageExpiryTest extends BaseJpaR4Test {
	private static final Logger ourLog = LoggerFactory.getLogger(FhirResourceDaoR4SearchPageExpiryTest.class);

	@After()
	public void after() {
		StaleSearchDeletingSvcImpl staleSearchDeletingSvc = AopTestUtils.getTargetObject(myStaleSearchDeletingSvc);
		staleSearchDeletingSvc.setCutoffSlackForUnitTest(StaleSearchDeletingSvcImpl.DEFAULT_CUTOFF_SLACK);
		StaleSearchDeletingSvcImpl.setNowForUnitTests(null);
	}

	@Before
	public void before() {
		StaleSearchDeletingSvcImpl staleSearchDeletingSvc = AopTestUtils.getTargetObject(myStaleSearchDeletingSvc);
		staleSearchDeletingSvc.setCutoffSlackForUnitTest(0);
	}

	@Before
	public void beforeDisableResultReuse() {
		myDaoConfig.setReuseCachedSearchResultsForMillis(null);
	}

	@Test
	public void testExpirePagesAfterReuse() throws Exception {
		IIdType pid1;
		IIdType pid2;
		{
			Patient patient = new Patient();
			patient.addName().setFamily("EXPIRE");
			pid1 = myPatientDao.create(patient, mySrd).getId().toUnqualifiedVersionless();
		}
		Thread.sleep(10);
		{
			Patient patient = new Patient();
			patient.addName().setFamily("EXPIRE");
			pid2 = myPatientDao.create(patient, mySrd).getId().toUnqualifiedVersionless();
		}
		Thread.sleep(10);

		myDaoConfig.setExpireSearchResultsAfterMillis(1000L);
		myDaoConfig.setReuseCachedSearchResultsForMillis(500L);
		long start = System.currentTimeMillis();

		final String searchUuid1;
		{
			SearchParameterMap params = new SearchParameterMap();
			params.add(Patient.SP_FAMILY, new StringParam("EXPIRE"));
			final IBundleProvider bundleProvider = myPatientDao.search(params);
			assertThat(toUnqualifiedVersionlessIds(bundleProvider), containsInAnyOrder(pid1, pid2));
			searchUuid1 = bundleProvider.getUuid();
			Validate.notBlank(searchUuid1);
		}

		sleepAtLeast(250);

		String searchUuid2;
		{
			SearchParameterMap params = new SearchParameterMap();
			params.add(Patient.SP_FAMILY, new StringParam("EXPIRE"));
			final IBundleProvider bundleProvider = myPatientDao.search(params);
			assertThat(toUnqualifiedVersionlessIds(bundleProvider), containsInAnyOrder(pid1, pid2));
			searchUuid2 = bundleProvider.getUuid();
			Validate.notBlank(searchUuid2);
		}
		assertEquals(searchUuid1, searchUuid2);

		sleepAtLeast(500);

		// We're now past 500ms so we shouldn't reuse the search

		final String searchUuid3;
		{
			SearchParameterMap params = new SearchParameterMap();
			params.add(Patient.SP_FAMILY, new StringParam("EXPIRE"));
			final IBundleProvider bundleProvider = myPatientDao.search(params);
			assertThat(toUnqualifiedVersionlessIds(bundleProvider), containsInAnyOrder(pid1, pid2));
			searchUuid3 = bundleProvider.getUuid();
			Validate.notBlank(searchUuid3);
		}
		assertNotEquals(searchUuid1, searchUuid3);

		// Search just got used so it shouldn't be deleted

		myStaleSearchDeletingSvc.pollForStaleSearchesAndDeleteThem();
		newTxTemplate().execute(new TransactionCallbackWithoutResult() {
			@Override
			protected void doInTransactionWithoutResult(TransactionStatus theArg0) {
				assertNotNull(mySearchEntityDao.findByUuid(searchUuid3));
			}
		});

		StaleSearchDeletingSvcImpl.setNowForUnitTests(start + 1400);

		myStaleSearchDeletingSvc.pollForStaleSearchesAndDeleteThem();
		newTxTemplate().execute(new TransactionCallbackWithoutResult() {
			@Override
			protected void doInTransactionWithoutResult(TransactionStatus theArg0) {
				assertNotNull(mySearchEntityDao.findByUuid(searchUuid3));
			}
		});
		newTxTemplate().execute(new TransactionCallbackWithoutResult() {
			@Override
			protected void doInTransactionWithoutResult(TransactionStatus theArg0) {
				assertNull(mySearchEntityDao.findByUuid(searchUuid1));
			}
		});

		StaleSearchDeletingSvcImpl.setNowForUnitTests(start + 2200);

		myStaleSearchDeletingSvc.pollForStaleSearchesAndDeleteThem();
		newTxTemplate().execute(new TransactionCallbackWithoutResult() {
			@Override
			protected void doInTransactionWithoutResult(TransactionStatus theArg0) {
				assertNull(mySearchEntityDao.findByUuid(searchUuid1));
				assertNull(mySearchEntityDao.findByUuid(searchUuid3));
			}
		});

	}

	@Test
	public void testExpirePagesAfterSingleUse() throws Exception {
		IIdType pid1;
		IIdType pid2;
		{
			Patient patient = new Patient();
			patient.addName().setFamily("EXPIRE");
			pid1 = myPatientDao.create(patient, mySrd).getId().toUnqualifiedVersionless();
		}
		Thread.sleep(10);
		{
			Patient patient = new Patient();
			patient.addName().setFamily("EXPIRE");
			pid2 = myPatientDao.create(patient, mySrd).getId().toUnqualifiedVersionless();
		}
		Thread.sleep(10);

		final StopWatch sw = new StopWatch();

		long start = System.currentTimeMillis();

		SearchParameterMap params;
		params = new SearchParameterMap();
		params.add(Patient.SP_FAMILY, new StringParam("EXPIRE"));
		final IBundleProvider bundleProvider = myPatientDao.search(params);
		assertThat(toUnqualifiedVersionlessIds(bundleProvider), containsInAnyOrder(pid1, pid2));
		assertThat(toUnqualifiedVersionlessIds(bundleProvider), containsInAnyOrder(pid1, pid2));

		myDaoConfig.setExpireSearchResultsAfterMillis(500);
		StaleSearchDeletingSvcImpl.setNowForUnitTests(start);

		myStaleSearchDeletingSvc.pollForStaleSearchesAndDeleteThem();
		TransactionTemplate txTemplate = new TransactionTemplate(myTxManager);
		txTemplate.execute(new TransactionCallbackWithoutResult() {
			@Override
			protected void doInTransactionWithoutResult(TransactionStatus theArg0) {
				assertNotNull("Failed after " + sw.toString(), mySearchEntityDao.findByUuid(bundleProvider.getUuid()));
			}
		});

		StaleSearchDeletingSvcImpl.setNowForUnitTests(start + 499);
		myStaleSearchDeletingSvc.pollForStaleSearchesAndDeleteThem();
		txTemplate.execute(new TransactionCallbackWithoutResult() {
			@Override
			protected void doInTransactionWithoutResult(TransactionStatus theArg0) {
				assertNotNull(mySearchEntityDao.findByUuid(bundleProvider.getUuid()));
			}
		});

		StaleSearchDeletingSvcImpl.setNowForUnitTests(start + 600);
		myStaleSearchDeletingSvc.pollForStaleSearchesAndDeleteThem();
		txTemplate.execute(new TransactionCallbackWithoutResult() {
			@Override
			protected void doInTransactionWithoutResult(TransactionStatus theArg0) {
				assertNull(mySearchEntityDao.findByUuid(bundleProvider.getUuid()));
			}
		});
	}
}
