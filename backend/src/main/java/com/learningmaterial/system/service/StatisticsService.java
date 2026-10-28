package com.learningmaterial.system.service;

import com.learningmaterial.system.dto.StatisticsDtos.*;

/** StatisticsService 的业务契约。 */
public interface StatisticsService {
    Statistics get(String rawFrom, String rawTo, String timezone);

}
