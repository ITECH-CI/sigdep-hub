SELECT
hiv_screening_hiv_screening.screening_date,
hiv_screening_hiv_screening.screening_code,
hiv_screening_hiv_screening.profession,
hiv_screening_hiv_screening.age,
hiv_screening_hiv_screening.gender,
hiv_screening_hiv_screening.residence,
IF ( population_type = 0 , 'Population Générale',
			IF( population_type = 1, 'UD',
				IF (population_type = 2, 'TS',
					IF (population_type = 3, 'HSH',
						IF(population_type = 4, 'PC',
							IF (population_type = 5, 'Autres', NULL)))))) AS population_type,
  IF ( hiv_screening_hiv_screening.screening_reason = 1 , 'IST',
			IF( hiv_screening_hiv_screening.screening_reason = 2, 'CONFIRMATION APRES AUTO-TEST',
				IF (hiv_screening_hiv_screening.screening_reason = 3, 'CONTACT-INDEX',
					IF (hiv_screening_hiv_screening.screening_reason = 4, 'FEMME ENCEINTE',
						IF(hiv_screening_hiv_screening.screening_reason = 5, 'FEMME ALLAITANTE',
							IF (hiv_screening_hiv_screening.screening_reason = 6, 'DEPISTAGE EN COUPLE',
								IF (hiv_screening_hiv_screening.screening_reason = 7, 'AES',
									IF(hiv_screening_hiv_screening.screening_reason = 8, 'PrEP',
										IF (hiv_screening_hiv_screening.screening_reason = 6, 'AUTRES(à PRECISER)',NULL))))))))) AS screening_reason,

hiv_screening_hiv_screening.other_screening_reason,
IF ( hiv_screening_hiv_screening.marital_status = 0 , 'Célibataire',
			IF( hiv_screening_hiv_screening.marital_status = 1, 'Couple',
				IF (hiv_screening_hiv_screening.marital_status = 2, 'Autre à préciser',NULL))) AS marital_status,
hiv_screening_hiv_screening.other_marital_status,
KIT1.batch_number  kit1batchnumber,
KIT1.expiry_date kit1expiry,
IF(hiv_screening_hiv_screening.test1_reaction=1, 'R', 'NR') as reacrtion_test1,
hiv_screening_hiv_screening.test2_reaction,
KIT2.batch_number kit2batchnumber,
KIT2.expiry_date kit2expiry,
IF(hiv_screening_hiv_screening.test3_reaction=1, 'R','')as reacrtion_test3,
IF(hiv_screening_hiv_screening.final_result=0, 'NEG',
  IF (hiv_screening_hiv_screening.final_result = 1, 'POS','IND')) as resultat_final_test,
hiv_screening_hiv_screening.result_announcing_date,
IF (hiv_screening_hiv_screening.retesting=1,'OUI', 'NON') retesting,
hiv_screening_hiv_screening.`comment`,
hiv_screening_screening_register_info.screening_site_type,
hiv_screening_hiv_screening.register_info,
if (hiv_screening_hiv_screening.sampling IS NOT NULL, 'OUI', NULL) EEQ,
hiv_screening_screening_register_info.screening_post,
hiv_screening_hiv_screening.location_id
FROM
hiv_screening_hiv_screening
INNER JOIN hiv_screening_screening_register_info ON hiv_screening_hiv_screening.register_info = hiv_screening_screening_register_info.screening_info_id
LEFT JOIN hiv_screening_testing_kit KIT1 ON hiv_screening_screening_register_info.testing1_kit = KIT1.testing_kit_id
LEFT JOIN hiv_screening_testing_kit KIT2 ON hiv_screening_screening_register_info.testing2_kit = KIT2.testing_kit_id
WHERE result_announcing_date BETWEEN :startDate AND :endDate AND hiv_screening_hiv_screening.location_id = :location
ORDER BY screening_date;