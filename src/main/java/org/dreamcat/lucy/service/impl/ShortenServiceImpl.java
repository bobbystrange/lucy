package org.dreamcat.lucy.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dreamcat.common.crypto.MD5Util;
import org.dreamcat.common.web.exception.InternalServerErrorException;
import org.dreamcat.common.web.exception.UnauthorizedException;
import org.dreamcat.lucy.component.HashGenerator;
import org.dreamcat.lucy.config.AppProperties;
import org.dreamcat.lucy.dao.AccountDao;
import org.dreamcat.lucy.entity.ShortenUrl;
import org.dreamcat.lucy.service.ShortenService;
import org.springframework.data.cassandra.core.CassandraTemplate;
import org.springframework.data.cassandra.core.InsertOptions;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Create by tuke on 2020/5/13
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class ShortenServiceImpl implements ShortenService {
    private final AccountDao accountDao;
    private final CassandraTemplate cassandraTemplate;
    private final HashGenerator hashGenerator;
    private final AppProperties properties;

    private AtomicInteger width;

    @PostConstruct
    public void init() {
        width = new AtomicInteger(properties.getHash().getMinWidth());
    }

    @Override
    public String shorten(String url, int ttl, String password, String token) {
        url = URLDecoder.decode(url, StandardCharsets.UTF_8);

        boolean unauthorized = false;
        if (ttl > 12 * 30 * 24 * 3600) {
            if (token == null) {
                unauthorized = true;
            }

            var account = accountDao.findByToken(token);
            if (account == null) unauthorized = true;
        }

        if (unauthorized) {
            throw new UnauthorizedException("require token");
        }

        var entity = new ShortenUrl();
        if (password != null) {
            entity.setPassword(MD5Util.md5Base64(password));
        }
        entity.setUrl(url);

        int i =  width.get();
        for (var hash = hashGenerator.hash(url, i++); i <= 12; ) {
            entity.setHash(hash);

            var res = cassandraTemplate.insert(entity, InsertOptions.builder()
                    .ttl(ttl)
                    .withIfNotExists()
                    .build());
            if (res.wasApplied()) {
                return hash;
            }
            // Note that repeat url, just return the existing hash
            var oldEntity = res.getEntity();
            if (url.equals(oldEntity.getUrl())) {
                return oldEntity.getHash();
            }
            // Note that there is a hash conflict
            var currentWidth = width.get();
            if (currentWidth < properties.getHash().getDesiredMaxWidth()) {
                width.compareAndSet(currentWidth, currentWidth + 1);
            }
        }

        throw new InternalServerErrorException("hash width is over 12");
    }

}
