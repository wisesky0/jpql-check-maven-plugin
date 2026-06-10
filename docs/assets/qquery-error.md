
### Querydsl error

```java
queryFactory
    .select(...).distinct()
    .from(qHmCmccProdCstcRellEntity, qHmCmccProdCstcRellEntity02)                                                  // ← FROM 루트
    .innerJoin(qHmCmccLoblEntity)
        .on(qHmCmccLoblEntity.loblCd.eq(qHmCmccProdCstcRellEntity02.trgtProdItemCdv)) // ★ 02 참조
    .innerJoin(qHmCmccSvcEntity)
        .on(qHmCmccSvcEntity.svcCd.eq(qHmCmccLoblEntity.svcCd))
    .innerJoin(qHmCmccRatSvcFctrRellEntity)
        .on(qHmCmccRatSvcFctrRellEntity.ratCd.eq(qHmCmccProdCstcRellEntity02.trgtProdItemCdv)) // ★ 02 참조
    .innerJoin(qHmCmccSvcFctrEntity)
        .on(qHmCmccSvcFctrEntity.svcFctrCd.eq(qHmCmccRatSvcFctrRellEntity.svcFctrCd))
    .innerJoin(qHmCmccProdEntity)
        .on(qHmCmccProdEntity.prodCd.eq(qHmCmccProdCstcRellEntity02.baseProdItemCdv))  // ★ 02 참조
    .where(qHmCmccProdCstcRellEntity.baseProdItemCdv.eq(prodCd)                        // ← 여기선 02 아닌 원본
        .and(qHmCmccSvcEntity.svcCd.eq(svcCd))
        .and(qHmCmccProdCstcRellEntity02.trgtProdItemCdv.notLike("LIPS"))             // where엔 02도 섞임
        .and(qHmCmccProdCstcRellEntity02.trgtProdItemCdv.notLike("BPSS"))
        .and(qHmCmccRatSvcFctrRellEntity.validEndDttm.isNull().or(...goe(strToday)))
        .and(qHmCmccProdCstcRellEntity02.validEndDttm.isNull().or(...goe(strToday))))
    .fetch();
```

Caused by: org.hibernate.query.SemanticException: SqmQualified Join predicate referred to SomRoot
[com.xxxs.nuoh.online.common.pc.entity.HmCmccProdCstcRellEntity(hmCmccProdCstcRellEytity)] other than the join's root
[com.xxxx.online.common.pc.entity.HmCmecLoblEntity(hmCmccLoblEntity)]
