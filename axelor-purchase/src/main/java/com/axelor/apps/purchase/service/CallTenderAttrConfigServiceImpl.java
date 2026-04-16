/*
 * Axelor Business Solutions
 *
 * Copyright (C) 2005-2026 Axelor (<http://axelor.com>).
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.axelor.apps.purchase.service;

import com.axelor.apps.purchase.db.CallTenderAttrConfig;
import com.axelor.apps.purchase.db.CallTenderNeed;
import com.axelor.apps.purchase.db.CallTenderOffer;
import com.axelor.apps.purchase.db.repo.CallTenderNeedRepository;
import com.axelor.apps.purchase.db.repo.CallTenderOfferRepository;
import com.axelor.common.StringUtils;
import com.axelor.meta.db.MetaJsonField;
import com.axelor.meta.db.repo.MetaJsonFieldRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;
import com.google.inject.persist.Transactional;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CallTenderAttrConfigServiceImpl implements CallTenderAttrConfigService {

  protected final MetaJsonFieldRepository metaJsonFieldRepository;
  protected final CallTenderNeedRepository callTenderNeedRepository;
  protected final CallTenderOfferRepository callTenderOfferRepository;
  protected final ObjectMapper objectMapper;

  @Inject
  public CallTenderAttrConfigServiceImpl(
      MetaJsonFieldRepository metaJsonFieldRepository,
      CallTenderNeedRepository callTenderNeedRepository,
      CallTenderOfferRepository callTenderOfferRepository) {
    this.metaJsonFieldRepository = metaJsonFieldRepository;
    this.callTenderNeedRepository = callTenderNeedRepository;
    this.callTenderOfferRepository = callTenderOfferRepository;
    this.objectMapper = new ObjectMapper();
  }

  @Override
  @Transactional
  public void syncOfferCustomFields(CallTenderAttrConfig config) {
    if (config == null || config.getId() == null) {
      return;
    }

    Map<String, MetaJsonField> needFieldByName = getNeedFieldByName(config);
    Map<String, MetaJsonField> offerFieldByName = getOfferFieldByName(config);

    for (Map.Entry<String, MetaJsonField> entry : needFieldByName.entrySet()) {
      MetaJsonField needField = entry.getValue();
      MetaJsonField offerField = offerFieldByName.remove(entry.getKey());
      if (offerField == null) {
        offerField = createOfferField(needField, config);
      }
      copyDefinition(needField, offerField);
      metaJsonFieldRepository.save(offerField);
    }

    for (MetaJsonField stale : offerFieldByName.values()) {
      metaJsonFieldRepository.remove(stale);
    }

    fillDefaultValues(config, needFieldByName);
  }

  protected void fillDefaultValues(
      CallTenderAttrConfig config, Map<String, MetaJsonField> needFieldByName) {

    Map<String, String> defaultsByName = new HashMap<>();
    for (MetaJsonField field : needFieldByName.values()) {
      if (StringUtils.notEmpty(field.getDefaultValue())) {
        defaultsByName.put(field.getName(), field.getDefaultValue());
      }
    }
    if (defaultsByName.isEmpty()) {
      return;
    }

    List<CallTenderNeed> needs =
        callTenderNeedRepository
            .all()
            .filter("self.product = :product")
            .bind("product", config.getProduct())
            .fetch();

    for (CallTenderNeed need : needs) {
      String updated = applyMissingDefaults(need.getAttrs(), defaultsByName);
      if (updated != null) {
        need.setAttrs(updated);
      }
    }

    List<CallTenderOffer> offers =
        callTenderOfferRepository
            .all()
            .filter("self.product = :product")
            .bind("product", config.getProduct())
            .fetch();

    for (CallTenderOffer offer : offers) {
      String updated = applyMissingDefaults(offer.getAttrs(), defaultsByName);
      if (updated != null) {
        offer.setAttrs(updated);
      }
    }
  }

  protected String applyMissingDefaults(String attrs, Map<String, String> defaultsByName) {
    Map<String, Object> map = new HashMap<>(parseAttrs(attrs));
    boolean changed = false;
    for (Map.Entry<String, String> entry : defaultsByName.entrySet()) {
      if (!map.containsKey(entry.getKey())) {
        map.put(entry.getKey(), entry.getValue());
        changed = true;
      }
    }
    if (!changed) {
      return null;
    }
    try {
      return objectMapper.writeValueAsString(map);
    } catch (JsonProcessingException e) {
      return null;
    }
  }

  @SuppressWarnings("unchecked")
  protected Map<String, Object> parseAttrs(String attrs) {
    if (StringUtils.isBlank(attrs)) {
      return Collections.emptyMap();
    }
    try {
      return objectMapper.readValue(attrs, Map.class);
    } catch (IOException e) {
      return Collections.emptyMap();
    }
  }

  protected Map<String, MetaJsonField> getNeedFieldByName(CallTenderAttrConfig config) {
    Map<String, MetaJsonField> map = new HashMap<>();
    if (config.getCustomFieldList() == null) {
      return map;
    }
    for (MetaJsonField f : config.getCustomFieldList()) {
      if (!StringUtils.isBlank(f.getName())) {
        map.put(f.getName(), f);
      }
    }
    return map;
  }

  protected Map<String, MetaJsonField> getOfferFieldByName(CallTenderAttrConfig config) {
    List<MetaJsonField> offerFields =
        metaJsonFieldRepository
            .all()
            .filter("self.callTenderOfferAttrConfig = :config AND self.model = :model")
            .bind("config", config)
            .bind("model", MODEL_OFFER)
            .fetch();

    Map<String, MetaJsonField> map = new HashMap<>();
    for (MetaJsonField f : offerFields) {
      if (!StringUtils.isBlank(f.getName())) {
        map.put(f.getName(), f);
      }
    }
    return map;
  }

  protected MetaJsonField createOfferField(MetaJsonField needField, CallTenderAttrConfig config) {
    MetaJsonField offerField = new MetaJsonField();
    offerField.setModel(MODEL_OFFER);
    offerField.setModelField(MODEL_FIELD);
    offerField.setName(needField.getName());
    offerField.setCallTenderOfferAttrConfig(config);
    return offerField;
  }

  protected void copyDefinition(MetaJsonField source, MetaJsonField target) {
    target.setTitle(source.getTitle());
    target.setType(source.getType());
    target.setWidget(source.getWidget());
    target.setDefaultValue(source.getDefaultValue());
    target.setRequired(source.getRequired());
    target.setSelection(source.getSelection());
    target.setScale(source.getScale());
    target.setPrecision(source.getPrecision());
    target.setMinSize(source.getMinSize());
    target.setMaxSize(source.getMaxSize());
    target.setRegex(source.getRegex());
    target.setSequence(source.getSequence());
    target.setVisibleInGrid(source.getVisibleInGrid());
    target.setContextField(source.getContextField());
    target.setContextFieldTarget(source.getContextFieldTarget());
    target.setContextFieldTargetName(source.getContextFieldTargetName());
    target.setContextFieldValue(source.getContextFieldValue());
    target.setContextFieldTitle(source.getContextFieldTitle());
  }
}
